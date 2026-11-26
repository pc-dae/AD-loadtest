# Local lab workflow. Override any load setting on the command line, e.g.
#   make test DURATION=300 LDAP_THREADS=20 KERBEROS_THREADS=20 NTLM_THREADS=10
PROPS            ?= lab.properties
DURATION         ?= 120
RAMPUP           ?= 10
LDAP_THREADS     ?= 5
KERBEROS_THREADS ?= 5
NTLM_THREADS     ?= 5
RUN_ID           ?= $(shell date +%Y%m%d-%H%M%S)
EXTRA            ?=

COMPOSE := docker compose

.PHONY: build up ui test smoke dashboard logs down clean images images-push

build: ## Build the samplers and controller locally (the Docker image also builds them)
	mvn -B -q package

up: ## Start the Samba AD DC, InfluxDB, Grafana, Telegraf and the web controller
	$(COMPOSE) up -d --build dc1 influxdb grafana telegraf controller
	@echo "Waiting for the domain controller..."
	@until [ "$$(docker inspect -f '{{.State.Health.Status}}' ad-loadtest-dc1-1)" = healthy ]; do sleep 3; done
	@echo "Lab ready. Web UI: http://localhost:8080  Dashboard: http://localhost:3000/d/ad-loadtest"

ui: ## Open the web front end
	open http://localhost:8080

test: ## Run the JMeter plan headless from the command line; results and HTML report in results/<run id>
	$(COMPOSE) build controller
	$(COMPOSE) run --rm jmeter -n -t /config/ad-auth.jmx -q /config/$(PROPS) \
	  -Jduration=$(DURATION) -Jrampup=$(RAMPUP) \
	  -Jldap.threads=$(LDAP_THREADS) -Jkerberos.threads=$(KERBEROS_THREADS) -Jntlm.threads=$(NTLM_THREADS) \
	  $(EXTRA) \
	  -l /results/$(RUN_ID)/results.jtl -j /results/$(RUN_ID)/jmeter.log -e -o /results/$(RUN_ID)/report
	@echo "Report: results/$(RUN_ID)/report/index.html"

smoke: ## 30-second sanity run with 1 thread per protocol
	$(MAKE) test DURATION=30 RAMPUP=1 LDAP_THREADS=1 KERBEROS_THREADS=1 NTLM_THREADS=1

dashboard: ## Open Grafana
	open http://localhost:3000/d/ad-loadtest

logs: ## Follow the domain controller log
	$(COMPOSE) logs -f dc1

down: ## Stop the lab, keeping the domain and metrics
	$(COMPOSE) down

clean: ## Stop the lab and delete the domain, metrics and results
	$(COMPOSE) down -v
	rm -rf data results target

# ---------------------------------------------------------------------------
# Images for Kubernetes. kind and EKS clusters are created and managed by mac-k8s from the
# pc-dae-gitops/aws-ad-loadtest config repository, which deploys charts/ad-loadtest from here.
# ---------------------------------------------------------------------------

images: ## Build the Samba DC and JMeter/controller images locally (tag :dev)
	$(COMPOSE) build dc1 controller

REGISTRY  ?= paulcarltondae
TAG       ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
PLATFORMS ?= linux/arm64,linux/amd64

images-push: ## Build multi-arch images and push to Docker Hub: make images-push TAG=0.1.0
	docker buildx build --platform $(PLATFORMS) -t $(REGISTRY)/ad-loadtest-samba-dc:$(TAG) --push docker/samba-dc
	docker buildx build --platform $(PLATFORMS) -t $(REGISTRY)/ad-loadtest-jmeter:$(TAG) -f docker/jmeter/Dockerfile --push .
