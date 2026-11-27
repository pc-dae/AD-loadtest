"""Creates load-test users and groups directly in sam.ldb and writes users.csv.

The scheme is shared with the Windows DC seeding (infra/aws/ad/dc1.ps1.tftpl) and the chart's
users CSV generator, so expectations always match the directory. For user i (1-based):

  i % 4 == 0   member of LT-Authorised                                 authorised
  i % 8 == 1   member of LT-Authorised-Nested (in LT-Authorised)       authorised, "nested"
  i % 8 == 5   member of LT-Nest-01, NEST_DEPTH groups deep            authorised, "deep"
  otherwise    member of LT-Denied                                     denied
  i % 10 == 0  also in ROLE_GROUPS LT-Role-nnn groups                  "heavy": large tokens
  i % 50 == 3  account disabled                                        logins rejected, denied
  i % 50 == 7  account expired                                         logins rejected, denied

It also creates COMPUTER_COUNT computer accounts LTPC00001$..., with the test user password so the
load generators can log on as them, and writes computers.csv next to users.csv.
"""
import csv
import os

from samba.auth import system_session
from samba.param import LoadParm
from samba.samdb import SamDB

count = int(os.environ.get("SEED_USERS", "500"))
role_groups = int(os.environ.get("ROLE_GROUPS", "200"))
nest_depth = int(os.environ.get("NEST_DEPTH", "8"))
computer_count = int(os.environ.get("COMPUTER_COUNT", "100"))
password = os.environ["TEST_USER_PASSWORD"]
service_password = os.environ.get("SERVICE_PASSWORD", password)
out = os.environ.get("USERS_CSV", "/export/users.csv")
computers_out = os.path.join(os.path.dirname(out), "computers.csv")

EXPIRED_SECONDS_AGO = 365 * 24 * 3600


def profile_of(i):
    """Returns (base group, profile, authorised, expect) for user i."""
    if i % 4 == 0:
        group = "LT-Authorised"
    elif i % 8 == 1:
        group = "LT-Authorised-Nested"
    elif i % 8 == 5:
        group = "LT-Nest-01"
    else:
        group = "LT-Denied"
    disabled, expired = i % 50 == 3, i % 50 == 7
    if disabled:
        profile = "disabled"
    elif expired:
        profile = "expired"
    elif i % 10 == 0:
        profile = "heavy"
    elif group == "LT-Nest-01":
        profile = "deep"
    elif group == "LT-Authorised-Nested":
        profile = "nested"
    else:
        profile = "standard"
    authorised = group != "LT-Denied" and not (disabled or expired)
    return group, profile, authorised, "fail" if disabled or expired else "ok"


lp = LoadParm()
lp.load_default()
samdb = SamDB(url="/var/lib/samba/private/sam.ldb", session_info=system_session(), lp=lp)

nest_groups = [f"LT-Nest-{d:02d}" for d in range(1, nest_depth + 1)]
role_group_names = [f"LT-Role-{r:03d}" for r in range(1, role_groups + 1)]
for group in ["LT-Authorised", "LT-Authorised-Nested", "LT-Denied"] + nest_groups + role_group_names:
    samdb.newgroup(group)
samdb.add_remove_group_members("LT-Authorised", ["LT-Authorised-Nested"], add_members_operation=True)
# LT-Nest-01 is in LT-Nest-02 ... and the last one is in LT-Authorised.
for inner, outer in zip(nest_groups, nest_groups[1:] + ["LT-Authorised"]):
    samdb.add_remove_group_members(outer, [inner], add_members_operation=True)

samdb.newuser("svc-ltapp", service_password)

members = {}
heavy = []
rows = []
for i in range(1, count + 1):
    name = f"ltuser{i:05d}"
    samdb.newuser(name, password)
    group, profile, authorised, expect = profile_of(i)
    members.setdefault(group, []).append(name)
    if i % 10 == 0:
        heavy.append(name)
    if profile == "disabled":
        samdb.disable_account(f"(sAMAccountName={name})")
    elif profile == "expired":
        samdb.setexpiry(f"(sAMAccountName={name})", -EXPIRED_SECONDS_AGO)
    rows.append((name, password, "true" if authorised else "false", profile, expect))
    if i % 100 == 0:
        print(f"  created {i}/{count} users", flush=True)

for group, names in members.items():
    samdb.add_remove_group_members(group, names, add_members_operation=True)
if heavy:
    for group in role_group_names:
        samdb.add_remove_group_members(group, heavy, add_members_operation=True)

computer_rows = []
for i in range(1, computer_count + 1):
    name = f"LTPC{i:05d}"
    samdb.newcomputer(name)
    samdb.setpassword(f"(sAMAccountName={name}$)", password, force_change_at_next_login=False)
    computer_rows.append((f"{name}$", password, "false", "computer", "ok"))

os.makedirs(os.path.dirname(out), exist_ok=True)
header = ("username", "password", "authorised", "profile", "expect")
for path, data in ((out, rows), (computers_out, computer_rows)):
    with open(path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(header)
        writer.writerows(data)
print(f"Seeded {count} users, {len(heavy)} in {role_groups} role groups, nesting {nest_depth} deep, "
      f"and {computer_count} computers; wrote {out} and {computers_out}")
