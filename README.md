> **Confidentiality:** Internal
> **Document status:** DRAFT - UNREVIEWED

# AD-loadtest

A JMeter load tester for Active Directory **authentication and authorisation** over **LDAP, Kerberos and NTLM**. You can drive it from a **web front end** or the **command line**, and watch results on a live Grafana dashboard.

It runs in three ways, all from the same images and test plan:

| Mode | Domain controller | Use it for |
|---|---|---|
| **Docker Compose** (`make up`) | Samba 4 AD DC container | Fastest local loop, no cluster |
| **kind** (via [aws-ad-loadtest](https://github.com/pc-dae-gitops/aws-ad-loadtest) and mac-k8s `setup.sh --kind`) | Samba 4 AD DC pod | Local Kubernetes with Flux, the same chart that runs on EKS |
| **AWS** (via [aws-ad-loadtest](https://github.com/pc-dae-gitops/aws-ad-loadtest)) | Windows Server DCs on EC2 | Real numbers; ephemeral, spun up and torn down per session |

## What it measures

| Transaction | Protocol | What happens per sample | Passes when |
|---|---|---|---|
| `LDAP bind` | LDAP(S) | New connection, TLS handshake, simple bind as the user | Bind succeeds |
| `LDAP authz (group in chain)` | LDAP(S) | Bind, then a nested-group membership check (`LDAP_MATCHING_RULE_IN_CHAIN`) against `LT-Authorised` | Result matches the user's `authorised` column |
| `Kerberos TGT (AS)` | Kerberos | AS-REQ/AS-REP including the pre-authentication round trip | TGT issued |
| `Kerberos service ticket (TGS)` | Kerberos | TGS-REQ for `kerberos.spn` using that TGT, then builds the AP-REQ (this is where AD assembles the PAC with group memberships) | Service ticket issued |
| `NTLM SMB session` | NTLMv2 over SMB2 | Session setup with NTLMSSP, tree connect and listing of `netlogon` on the DC | Session established |
| `Computer Kerberos TGT (AS)` / `… service ticket (TGS)` | Kerberos | The same two steps with a **computer account** (`LTPC00001$`…, from `computers.csv`), as a machine does at boot and for Group Policy. Off by default | TGT and ticket issued |

One iteration of a protocol's thread group is one **login attempt**: LDAP bind + group check, Kerberos TGT + service ticket, one NTLM session, or one computer logon. Rates and attempt totals count these.

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

- choose a target profile, duration, ramp-up, think time and authorisation mode, and per protocol the **threads**, a **target rate** (attempts per second) and a **total number of attempts** (see [Load shape](#load-shape-failure-tests-and-multiple-dcs))
- add a **bad-password percentage** for failure testing, pick the **target DCs**, and the number of **load generators**
- **label** runs (for example `baseline` and `agent on`) and **compare** two of them side by side: response times, errors and DC resource use (see [Comparing runs](#comparing-runs-agent-impact))
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
| `POST /api/runs` | Start a run; form fields `profile, label, duration, rampup, thinkMs, authzBindAs`, per protocol (`ldap`, `kerberos`, `ntlm`, `computer`) `<p>Threads, <p>Rate, <p>Attempts`, and `badPasswordPct, badPasswordMaxPerUser, targetHosts, generators` |
| `GET /api/compare?a=<run>&b=<run>` | Two finished runs side by side |
| `POST /api/runs/stop` | Stop the current run |
| `GET /api/profiles` | Available target profiles |

```bash
curl -X POST localhost:8080/api/runs -d 'duration=300&ldapThreads=20&kerberosThreads=20&ntlmThreads=10'
# 10,000 Kerberos logins in 5 minutes (33.3/s), 5% of them with a wrong password
curl -X POST localhost:8080/api/runs -d 'label=10k-5min&duration=330&ldapThreads=0&ntlmThreads=0&kerberosThreads=20&kerberosRate=33.4&kerberosAttempts=10000&badPasswordPct=5'
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

Setting a protocol's threads to `0` disables it, for example `make test NTLM_THREADS=0`. Other settings go in `EXTRA`, for example `make test EXTRA="-Jkerberos.rate=33.4 -Jkerberos.iterations=10000 -JbadPassword.percent=5 -Jcomputer.threads=2"`.

## Load shape, failure tests and multiple DCs

These are JMeter properties (see `jmeter/lab.properties`), and the web form sets them per run.

| Property | Effect |
|---|---|
| `<protocol>.rate` | Target attempts per second for the whole run, shared by the protocol's threads and spaced evenly. It's a ceiling: if the threads can't keep up, the run falls behind rather than bursting. Give it enough threads (rate × typical response time, plus headroom). |
| `<protocol>.iterations` | Total attempts for the run. The protocol's threads stop once all have started, so the run ends on the count or on `duration`, whichever comes first. Together with a rate this gives requirements such as *10,000 attempts in 5 minutes* or *100,000 in 15 or 30 minutes*. |
| `badPassword.percent` | Share of attempts sent with a wrong password, labelled `[bad-password]` and expected to be **rejected** (a rejection passes, an acceptance fails). |
| `badPassword.maxPerUser` | Cap on bad attempts per user in a run, per load generator. **Set it below the domain's lockout threshold** on any domain with lockout; the lab domains have lockout turned off. |
| `ad.host` | One DC or several, comma separated. Each thread sticks to one DC, as a Windows client does once the DC locator has chosen. Kerberos uses the first DC in the list as its KDC (Java only fails over, it doesn't balance), so each load generator starts the list at a different DC. The krb5.conf is generated from this list; set `kerberos.config=file` to use your own. |

The `<protocol>` prefixes are `ldap`, `kerberos`, `ntlm` and `computer`. Expected rejections (disabled, expired, bad password) count as passes, so the error rate measures real failures only.

### Several load generators

One JMeter process managed about 450 samples per second against the laptop lab, which is above the target rates in the requirements. For more load, the controller can drive **JMeter servers** as extra generators. It sends each one the plan and the run's settings, divides rates and attempt totals between them, and collects every sample into one results file, report and live dashboard.

- **Compose:** `make up-distributed` starts `gen1` and `gen2`, and the web form shows a *Load generators* choice.
- **Kubernetes:** set `generators.replicas` in the chart (a StatefulSet of JMeter servers).

Thread counts are per run and must be a multiple of the number of generators used, so each runs the same share.

## Comparing runs (agent impact)

To measure the impact of a security agent (or any other change) on the DCs, run the same load twice with labels such as `baseline` and `agent on`, then pick both under **Compare runs**. For each transaction the page shows samples, throughput, mean, p95 and p99 response times, and error rate, with the change from A to B. Mean and p95 changes beyond ±5% are highlighted.

For each DC it shows resource use over each run's steady state (after ramp-up):

- **On Windows DCs:** CPU, LSASS CPU and working set, available memory, LDAP binds, searches and bind time, Kerberos and NTLM authentications, ATQ latency and queue, and Netlogon semaphore waiters and timeouts.
- **In the Docker lab:** CPU and memory of the Samba container.

Run start and end appear as annotations on the Grafana dashboard, titled with the run id and label, and the comparison links to each run's time window. For a fair comparison, keep the threads, rate, attempts and target DCs the same across both runs.

## Running in Kubernetes

Clusters aren't created from this repository. mac-k8s creates them, driven by the config repository [pc-dae-gitops/aws-ad-loadtest](https://github.com/pc-dae-gitops/aws-ad-loadtest):

- `setup.sh --kind` creates a kind cluster.
- Flux's apps ResourceSet deploys `charts/ad-loadtest` from this repository's `main` branch, using the published images (`make images-push TAG=…`).
- `deploy-apps.sh` writes the app's secrets to Vault.
- `setup.sh --kind --target eks` adds the AWS target.

See that repository's README.

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
| `controller.grafanaUrl` | `http://localhost:3000` | Browser-facing Grafana URL for the embedded dashboard |

The chart is deployed as an entry in the config repository's `apps.yaml`. See `local-cluster` and `target-cluster` there for the kind and EKS values.

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

Every directory is seeded with the same deterministic scheme, and the users CSV is generated with it too, so every sample knows its expected outcome. That covers the Samba lab, the Windows DCs in AWS (`infra/aws/ad/dc1.ps1.tftpl`) and the chart's CSV generator. For user `ltuserNNNNN`, numbered `i`:

| Profile | Users (i) | Directory setup | Expected |
|---|---|---|---|
| standard | rest | `LT-Authorised` (i % 4 = 0) or `LT-Denied` | Logins succeed; authorised only in `LT-Authorised` |
| nested | i % 8 = 1 | `LT-Authorised-Nested`, one level inside `LT-Authorised` | Authorised |
| **deep** | i % 8 = 5 | `LT-Nest-01`, a chain of `nestDepth` (8) groups ending in `LT-Authorised` | Authorised; stresses nested lookups |
| **heavy** | i % 10 = 0 | Also in `roleGroups` (200) `LT-Role-nnn` groups | Logins succeed; large Kerberos tickets and token expansion |
| **disabled** | i % 50 = 3 | Account disabled | Logins **rejected** (counted as a pass); never authorised |
| **expired** | i % 50 = 7 | Account expired in 2020 | Logins **rejected** (counted as a pass); never authorised |

With 500 users that gives 315 standard, 58 nested, 57 deep, 50 heavy, 10 disabled and 10 expired users, 240 of them authorised.

The users CSV has the columns `username,password,authorised,profile,expect`:
- A rejected login is a pass when `expect` is `fail`, and an accepted one is a failure (`UNEXPECTED_SUCCESS`).
- Authorisation means being in the group (directly or nested) **and** enabled **and** not expired, the same check an application should make.
- Samples for the four bold profiles are labelled separately, e.g. `LDAP bind [heavy]`, so the dashboard shows their effect. They keep their test's colour, with a dashed or dotted line for the profile. Set `label.byProfile=false` to merge them.

Change the sizes with `SEED_USERS`, `ROLE_GROUPS` and `NEST_DEPTH` (Compose), `users.count`, `users.roleGroups` and `users.nestDepth` (chart), or `ad_user_count`, `ad_role_groups` and `ad_nest_depth` (AWS).

Lockout, password expiry and complexity are disabled in the lab domain. Lab passwords default to the values in `docker-compose.yml` and can be overridden with `ADMIN_PASSWORD` and `TEST_USER_PASSWORD`. **They are for a throwaway lab only.**

### Editing the plan

Install JMeter (`brew install jmeter`), copy `samplers/target/ad-loadtest.jar` (from `make build`) into JMeter's `lib/ext`, and open `jmeter/ad-auth.jmx`. Run tests with `make test` rather than the GUI.

For ad-hoc debugging, the DC is also reachable from the Mac on loopback: LDAP `1389`, LDAPS `1636`, Kerberos `1088` and SMB `1445`. These are remapped because macOS uses port 88 for its own KDC.

## Dashboard

**AD authentication load test** (Grafana, auto-provisioned):

- **Overview:** total requests, throughput, mean and p95 response times, errors, active threads
- **Per transaction:** throughput, p95 and mean response time, and errors over time, plus a summary table
- **Errors by response code:** what failed and why
- **Domain controller (container):** CPU and memory of the Samba DC container (Compose)
- **Domain controllers (Windows):** CPU, LSASS CPU and memory, Kerberos and NTLM authentications, LDAP binds and searches, ATQ latency and Netlogon semaphore waiters, per DC (AWS, from Telegraf on each DC)
- **Annotations** mark each run's start and end with its id and label

The dashboard is generated by `scripts/gen_dashboard.py`; edit that and re-run it rather than the JSON.

Each transaction keeps a fixed colour on every panel.

## Project layout

```
samplers/                                  JMeter Java samplers (ldap/, kerberos/, ntlm/)
controller/                                Web front end: JDK HTTP server, JSON API, static UI
jmeter/ad-auth.jmx                         Test plan
jmeter/lab.properties, krb5.conf           Target configuration
docker/samba-dc/                           Samba AD DC image, provisioning and user seeding
docker/jmeter/                             JMeter 5.6.3 image (multi-stage; builds samplers + controller; used by both)
charts/ad-loadtest/                        Helm chart deployed by aws-ad-loadtest (kind and EKS); dashboards/ holds the Grafana dashboard
infra/aws/ad/                              Terraform: Windows Server domain controllers in the EKS VPC
infra/aws/flux/                            tofu-controller Terraform object and secret copy, applied from aws-ad-loadtest
bin/                                       aws-status.sh, aws-teardown.sh
scripts/gen_dashboard.py                   Generates the Grafana dashboard JSON
docker/grafana/, docker/telegraf/          Compose-mode Grafana provisioning and metrics collection
```

## Known limitations

- **Samba is not Windows.** Latency and throughput against Samba say nothing about Windows DC capacity. The local lab proves the tooling and the test design, and the AWS phase produces the numbers that matter.
- **A single DC on a laptop saturates early.** About 450 samples/s on an 8-core MacBook, with latency rising as threads are added beyond that. Load generator and DC also share the same CPU.
- **Windows DC metrics are untested on real DCs.** Telegraf's counter set (`infra/aws/ad/telegraf.ps1.tftpl`) and the relay into InfluxDB were checked with a simulated DC, but not yet on Windows. The first AWS run will confirm the counter names.
- **Kerberos balances per load generator, not per thread.** Java's Kerberos configuration is process-wide, so all of a generator's Kerberos traffic goes to its first DC. Use as many generators as DCs to spread Kerberos evenly. LDAP and NTLM spread per thread.
- **The bad-password cap is per generator.** With several generators, each one counts bad attempts separately. Divide the cap by the number of generators on a domain with lockout.
- **No DC metrics for the Samba DC in Kubernetes.** The container CPU and memory panels rely on Telegraf reading Docker, so they only populate in the Compose mode.
- **Stack.** The controller is a dependency-free JDK HTTP server with a static JavaScript UI. Metrics go to InfluxDB and run history is kept on disk, not in Micronaut/React/PostgreSQL/Prometheus. The function is the same; changing stack would be a separate decision.
- **NTLM is tested over SMB**, not HTTP. Application-level NTLM/Negotiate to IIS arrives with phase 2.
- **LDAP binds are simple binds over TLS.** SASL/GSSAPI (Kerberos-authenticated LDAP) binds are not yet implemented.

## AWS

The cluster lifecycle is managed from the config repository [pc-dae-gitops/aws-ad-loadtest](https://github.com/pc-dae-gitops/aws-ad-loadtest), using mac-k8s. Its README has the up, use and tear-down steps. This repository provides what that config repository deploys:

| Path | Deployed by | What it is |
|---|---|---|
| `charts/ad-loadtest` | the apps ResourceSet, on kind and on EKS | The load tester. On EKS it runs with `samba.enabled=false`, `secrets.existingSecret=ad-loadtest-ad`, `dc.hostsSecretKey=dc_ips` (all DCs, from Terraform) and `dcMetrics.enabled=true` |
| `infra/aws/flux` | `ad-loadtest-infra` Kustomization on EKS | The tofu-controller `Terraform` object, plus a ResourceSet that copies the domain passwords into the app's namespace |
| `infra/aws/ad` | tofu-controller | Windows Server 2022 domain controllers in the EKS VPC's private subnets: no public access, fixed IPs, AD ports open only to the VPC |
| `bin/aws-status.sh` | you | What's running in the account and roughly what it costs |
| `bin/aws-teardown.sh` | you | Destroys the domain controllers. Run it **before** `eks-teardown.sh` |

### What the domain controllers do

On first boot, each DC runs a PowerShell script (`infra/aws/ad/*.ps1.tftpl`). The scripts are idempotent and re-run after every reboot, so each run carries on from where the last one stopped.

- **DC1** renames itself and creates the `lab.local` forest. It then installs an enterprise CA, so domain controllers enrol for the certificate LDAPS needs. Finally it disables lockout, expiry and complexity, and seeds `svc-ltapp`, `ltuser00001` to `ltuser00500` and the `LT-*` groups, using the same scheme as the Samba lab.
  It also creates 100 computer accounts (`LTPC00001$`…, `ad_computer_count`) for machine-logon tests.
- **DC2** waits for DC1, then joins as a replica. Set `ad_dc_count: "1"` to save cost.
- **Both** install Telegraf (version pinned and SHA-256 checked) as a service that serves the DC's performance counters on port 9273. The chart's `dc-metrics` deployment scrapes them into InfluxDB for the dashboard and run comparisons. Only the VPC can reach the port.

Terraform generates the passwords and puts them in the outputs Secret. They're also passed in the instance user data, because the `tf-runner` role only has EC2 permissions, so there's no instance profile for Parameter Store. That's fine for a disposable lab and not for anything real. A new AMI or a script change never replaces a running DC (`ignore_changes`).

### Cost guard rails

The sandbox account is limited to 6 days and $75. Running everything costs about $0.45/hour:

| Item | Cost |
|---|---|
| EKS control plane | $0.10/h |
| 2 × t3.large nodes | $0.19/h |
| 2 × t3.medium Windows DCs | $0.12/h |
| NAT gateway and NLB | $0.08/h |

Use it in sessions and tear down in between. `bin/aws-status.sh` shows anything left running.
