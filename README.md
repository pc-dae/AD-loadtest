> **Confidentiality:** Internal
> **Document status:** DRAFT - UNREVIEWED

# AD-loadtest

A JMeter load tester for Active Directory **authentication and authorisation** over **LDAP, Kerberos and NTLM**. You can drive it from a **web front end** or the **command line**, and watch results on a live Grafana dashboard.

It runs in three ways, all from the same images and test plan:

| Mode | Domain controller | Use it for |
|---|---|---|
| **Docker Compose** (`make up`) | Samba 4 AD DC container | Fastest local loop, no cluster |
| **kind + Helm** (`make kind-up`) | Samba 4 AD DC pod | Local Kubernetes, same chart that later runs on EKS |
| **AWS** (planned) | Windows Server DCs on EC2 | Real numbers; ephemeral, spun up and torn down per session |

## What it measures

| Transaction | Protocol | What happens per sample | Passes when |
|---|---|---|---|
| `LDAP bind` | LDAP(S) | New connection, TLS handshake, simple bind as the user | Bind succeeds |
| `LDAP authz (group in chain)` | LDAP(S) | Bind, then a nested-group membership check (`LDAP_MATCHING_RULE_IN_CHAIN`) against `LT-Authorised` | Result matches the user's `authorised` column |
| `Kerberos TGT (AS)` | Kerberos | AS-REQ/AS-REP including the pre-authentication round trip | TGT issued |
| `Kerberos service ticket (TGS)` | Kerberos | TGS-REQ for `kerberos.spn` using that TGT, then builds the AP-REQ (this is where AD assembles the PAC with group memberships) | Service ticket issued |
| `NTLM SMB session` | NTLMv2 over SMB2 | Session setup with NTLMSSP, tree connect and listing of `netlogon` on the DC | Session established |

Each sample uses a fresh connection or context, so nothing is cached between samples and every login really reaches the DC. Failures are reported with a stable code, for example `INVALID_CREDENTIALS`, `KDC_ERR_PREAUTH_FAILED`, `NT_STATUS_C000006D` or `UNEXPECTED_AUTHORISED`, and the dashboard groups errors by that code.

## Architecture (local)

```
 browser ──► Web controller :8080 ─┐ (or `make test` from the CLI)
                                   │ starts         docker network "lab"
 ┌──────────────┐  LDAPS/Kerberos/SMB  ┌──────────────────────┐
 │ JMeter       │ ───────────────────► │ dc1.lab.local        │
 │ + samplers   │                      │ Samba 4 AD DC        │
 └──────┬───────┘                      │ 500 seeded users     │
        │ Backend Listener             └──────────┬───────────┘
        ▼                                         │ container CPU/mem
 ┌──────────────┐   ┌──────────────┐    ┌─────────┴────┐
 │ InfluxDB 1.x │◄──│ Telegraf     │◄───│ Docker API   │
 └──────┬───────┘   └──────────────┘    └──────────────┘
        ▼
 ┌──────────────┐
 │ Grafana      │  http://localhost:3000/d/ad-loadtest
 └──────────────┘
```

## Quick start

Prerequisites: Docker Desktop. Java and Maven are optional, because the JMeter image builds the samplers itself.

```bash
make up          # build + start the DC, InfluxDB, Grafana, Telegraf and web controller (first start provisions the domain, ~1 min)
make ui          # open the web front end, http://localhost:8080
make dashboard   # open Grafana directly (anonymous viewer; admin/admin to edit)
```

### From the web front end

The page at http://localhost:8080 lets you:

- choose a target profile, duration, ramp-up, threads per protocol, think time and authorisation mode
- **start** and **stop** runs (stop is graceful: JMeter finishes in-flight samples and still writes its report)
- watch live throughput, response time, error rate and active threads, plus the JMeter console
- see the Grafana dashboard embedded in the page
- browse **run history** (web and CLI runs) with links to each HTML report

Only one run can be active at a time. Any `*.properties` file in `jmeter/` appears as a target profile.

The controller exposes a small JSON API, which is also useful for scripting:

| Endpoint | |
|---|---|
| `GET /api/status` | Current or last run, live stats, console tail |
| `GET /api/runs` | Run history |
| `POST /api/runs` | Start a run; form fields `profile, duration, rampup, ldapThreads, kerberosThreads, ntlmThreads, thinkMs, authzBindAs` |
| `POST /api/runs/stop` | Stop the current run |
| `GET /api/profiles` | Available target profiles |

```bash
curl -X POST localhost:8080/api/runs -d 'duration=300&ldapThreads=20&kerberosThreads=20&ntlmThreads=10'
```

The controller has no authentication. It is published on loopback only, so put it behind authentication before exposing it anywhere else.

### From the command line

```bash
make smoke       # 30 s, 1 thread per protocol
make test        # default: 120 s, 5 threads per protocol
make test DURATION=300 LDAP_THREADS=20 KERBEROS_THREADS=20 NTLM_THREADS=10
```

Each run writes `results/<run id>/results.jtl`, `jmeter.log` and a JMeter HTML report at `results/<run id>/report/index.html`.

| Command | Effect |
|---|---|
| `make down` | Stop, keeping the domain and metrics |
| `make clean` | Stop and delete the domain, metrics and results |
| `make logs` | Follow the DC log |

Setting a protocol's threads to `0` disables it, for example `make test NTLM_THREADS=0`.

## Running in kind

Prerequisites: Docker, `kind`, `helm` and `kubectl`.

```bash
make kind-up      # create cluster "ad-loadtest", build + load images, helm install (Samba DC included)
make kind-ui      # web front end, http://localhost:18080
                  # Grafana:       http://localhost:13000/d/ad-loadtest
make kind-test DURATION=60 LDAP_THREADS=10   # command-line run inside the cluster
make kind-deploy  # re-apply the chart after changes
make kind-down    # delete the cluster
```

The kind ports are deliberately different from Docker Compose's, so both can run side by side.

### Helm chart

`charts/ad-loadtest` deploys:

- the web controller with JMeter
- InfluxDB
- Grafana, with the dashboard provisioned
- optionally, the Samba DC

Key values:

| Value | Default | |
|---|---|---|
| `samba.enabled` | `true` | Run the lab DC in-cluster. Set `false` and `dc.host` to target real DCs |
| `dc.host` | in-cluster Samba service | DC the load generators connect to |
| `domain.*` | `LAB.LOCAL` lab domain | Realm, NetBIOS name, UPN suffix, base DN, authorisation group, SPN |
| `secrets.existingSecret` | lab passwords | Secret with `adminPassword` and `testUserPassword` |
| `users.count` / `users.existingConfigMap` | `500` | Generated users CSV (same scheme as the seeding), or bring your own |
| `controller.grafanaUrl` | `http://localhost:13000` | Browser-facing Grafana URL for the embedded dashboard |

`values-kind.yaml` adds the NodePorts for kind. The chart is laid out to become an entry in a mac-k8s/Flux `apps.yaml`.

## Configuration

Shared settings live in [`jmeter/lab.properties`](jmeter/lab.properties). Each sampler parameter falls back to a JMeter property of the same name, so pointing the plan at a different domain means copying that file and running `make test PROPS=my-domain.properties` with a matching `krb5.conf`. Any property can also be overridden for one run, for example `EXTRA="-Jauthz.bindAs=service"`.

| Property | Purpose |
|---|---|
| `ad.host`, `ad.domain`, `ad.realm`, `ad.upnSuffix`, `ad.baseDn` | Target domain |
| `ldap.port`, `ldap.tls`, `ldap.trustAll`, `ldap.truststore` | LDAP transport; use a truststore rather than `trustAll` against real DCs |
| `authz.groupDn` | Group that grants access |
| `authz.bindAs` | `user` (bind as each user) or `service` (long-lived service-account connection, the typical app-server pattern) |
| `kerberos.spn` | Service to request tickets for, in `service@host` form |
| `smb.share`, `smb.minVersion`, `smb.maxVersion` | NTLM/SMB target |
| `users.csv` | `username,password,authorised` |
| `ldap.threads`, `kerberos.threads`, `ntlm.threads`, `rampup`, `duration`, `think.ms` | Load profile |

### Test users

On first start the DC creates `ltuser00001` to `ltuser00500` (`SEED_USERS` changes the count) and writes `data/users.csv`:

- a quarter are direct members of `LT-Authorised`
- a quarter are in `LT-Authorised-Nested`, which is itself a member of `LT-Authorised`, to exercise in-chain lookups
- half are in `LT-Denied` only

Lockout, password expiry and complexity are disabled in the lab domain. Lab passwords default to the values in `docker-compose.yml` and can be overridden with `ADMIN_PASSWORD` and `TEST_USER_PASSWORD`. **They are for a throwaway lab only.**

### Editing the plan

Install JMeter (`brew install jmeter`), copy `samplers/target/ad-loadtest.jar` (from `make build`) into JMeter's `lib/ext`, and open `jmeter/ad-auth.jmx`. Run tests with `make test` rather than the GUI.

For ad-hoc debugging, the DC is also reachable from the Mac on loopback: LDAP `1389`, LDAPS `1636`, Kerberos `1088` and SMB `1445`. These are remapped because macOS uses port 88 for its own KDC.

## Dashboard

**AD authentication load test** (Grafana, auto-provisioned):

- **Overview:** total requests, throughput, mean and p95 response times, errors, active threads
- **Per transaction:** throughput, p95 and mean response time, and errors over time, plus a summary table
- **Errors by response code:** what failed and why
- **Domain controller:** CPU and memory of the DC container

Each transaction keeps a fixed colour on every panel.

## Project layout

```
samplers/                                  JMeter Java samplers (ldap/, kerberos/, ntlm/)
controller/                                Web front end: JDK HTTP server, JSON API, static UI
jmeter/ad-auth.jmx                         Test plan
jmeter/lab.properties, krb5.conf           Target configuration
docker/samba-dc/                           Samba AD DC image, provisioning and user seeding
docker/jmeter/                             JMeter 5.6.3 image (multi-stage; builds samplers + controller; used by both)
charts/ad-loadtest/                        Helm chart (kind now, EKS later); dashboards/ holds the Grafana dashboard
kind/cluster.yaml                          Local kind cluster with NodePort mappings
docker/grafana/, docker/telegraf/          Compose-mode Grafana provisioning and metrics collection
```

## Known limitations (phase 1)

- **Samba is not Windows.** Latency and throughput against Samba say nothing about Windows DC capacity. The local lab proves the tooling and the test design, and the AWS phase produces the numbers that matter.
- **A single DC on a laptop saturates early.** About 450 samples/s on an 8-core MacBook, with latency rising as threads are added beyond that. Load generator and DC also share the same CPU.
- **No DC metrics in kind.** The DC CPU and memory panels rely on Telegraf reading Docker, so they only populate in the Compose mode.
- **NTLM is tested over SMB**, not HTTP. Application-level NTLM/Negotiate to IIS arrives with phase 2.
- **LDAP binds are simple binds over TLS.** SASL/GSSAPI (Kerberos-authenticated LDAP) binds are not yet implemented.

## AWS (planned)

The target is a time-limited, budget-capped sandbox account (6-day lease, $75), so the AWS deployment is **ephemeral by design**: one command brings it up, one tears it down, nothing runs while idle, and everything is rebuilt from git.

1. **Management:** the user's existing pattern. A local kind cluster runs Flux and tofu-controller (see `pc-dae-gitops/mac-k8s` and `aws-eks-voters`), and Terraform state lives in S3.
2. **Infrastructure:** this repository's `infra/aws/` (to be added) holds Terraform and the tofu-controller `Terraform` objects for:
   - a VPC with two Windows Server DCs, seeded by PowerShell with the same user scheme
   - an optional IIS member server for HTTP NTLM and Negotiate tests
3. **Load generation:** the same Helm chart on EKS with `samba.enabled=false` and `dc.host` set to the Windows DCs, running inside the VPC so the DCs are never exposed publicly.
4. **Telemetry:** DC performance counters (LDAP searches/s, Kerberos AS/TGS requests/s, NTLM authentications/s, LSASS CPU) collected with Telegraf on Windows and shown on the same dashboard.
5. **Cost:** small instance sizes, a single NAT gateway, and `up` / `down` scripts with a teardown check.
