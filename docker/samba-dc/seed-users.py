"""Creates load-test users and groups directly in sam.ldb and writes users.csv.

Users are split four ways so authorisation checks see a realistic mix:
  i % 4 == 0  direct member of LT-Authorised
  i % 4 == 1  member of LT-Authorised-Nested (itself a member of LT-Authorised)
  otherwise   member of LT-Denied only
"""
import csv
import os

from samba.auth import system_session
from samba.param import LoadParm
from samba.samdb import SamDB

count = int(os.environ.get("SEED_USERS", "500"))
password = os.environ["TEST_USER_PASSWORD"]
service_password = os.environ.get("SERVICE_PASSWORD", password)
out = os.environ.get("USERS_CSV", "/export/users.csv")

lp = LoadParm()
lp.load_default()
samdb = SamDB(url="/var/lib/samba/private/sam.ldb", session_info=system_session(), lp=lp)

for group in ("LT-Authorised", "LT-Authorised-Nested", "LT-Denied"):
    samdb.newgroup(group)
samdb.add_remove_group_members("LT-Authorised", ["LT-Authorised-Nested"], add_members_operation=True)

samdb.newuser("svc-ltapp", service_password)

members = {"LT-Authorised": [], "LT-Authorised-Nested": [], "LT-Denied": []}
rows = []
for i in range(1, count + 1):
    name = f"ltuser{i:05d}"
    samdb.newuser(name, password)
    group = {0: "LT-Authorised", 1: "LT-Authorised-Nested"}.get(i % 4, "LT-Denied")
    members[group].append(name)
    rows.append((name, password, "true" if group != "LT-Denied" else "false"))
    if i % 100 == 0:
        print(f"  created {i}/{count} users", flush=True)

for group, names in members.items():
    if names:
        samdb.add_remove_group_members(group, names, add_members_operation=True)

os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", newline="") as f:
    writer = csv.writer(f)
    writer.writerow(("username", "password", "authorised"))
    writer.writerows(rows)
print(f"Seeded {count} users; wrote {out}")
