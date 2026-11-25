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
KIND_CLUSTER ?= ad-loadtest
NAMESPACE    ?= ad-loadtest
RELEASE      ?= ad-loadtest
KUBECTL      := kubectl --context kind-$(KIND_CLUSTER) -n $(NAMESPACE)

.PHONY: build up ui test smoke dashboard logs down clean images images-push kind-up kind-deploy kind-test kind-ui kind-down

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
# kind: the same stack deployed by the Helm chart into a local Kubernetes cluster
# ---------------------------------------------------------------------------

images: ## Build the Samba DC and JMeter/controller images (tag :dev)
	$(COMPOSE) build dc1 controller

REGISTRY ?= paulcalrtondae
TAG      ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)

images-push: images ## Tag and push both images to Docker Hub: make images-push REGISTRY=... TAG=...
	for img in ad-loadtest-samba-dc ad-loadtest-jmeter; do \
	  docker tag $$img:dev $(REGISTRY)/$$img:$(TAG) && docker push $(REGISTRY)/$$img:$(TAG); \
	done

kind-up: images ## Create the kind cluster (if needed), load images and deploy the chart
	@kind get clusters | grep -qx $(KIND_CLUSTER) || kind create cluster --config kind/cluster.yaml
	kind load docker-image --name $(KIND_CLUSTER) ad-loadtest-samba-dc:dev ad-loadtest-jmeter:dev
	$(MAKE) kind-deploy
	@echo "Web UI: http://localhost:18080  Grafana: http://localhost:13000/d/ad-loadtest"

kind-deploy: ## (Re)deploy the Helm chart to the kind cluster
	helm upgrade --install $(RELEASE) charts/ad-loadtest --kube-context kind-$(KIND_CLUSTER) \
	  -n $(NAMESPACE) --create-namespace -f charts/ad-loadtest/values-kind.yaml --wait --timeout 10m

kind-test: ## Command-line run inside the kind cluster (same variables as `make test`)
	$(KUBECTL) exec deploy/$(RELEASE)-controller -- jmeter -n -t /opt/ad-loadtest/plan/ad-auth.jmx \
	  -q /config/$(RELEASE).properties -Jduration=$(DURATION) -Jrampup=$(RAMPUP) \
	  -Jldap.threads=$(LDAP_THREADS) -Jkerberos.threads=$(KERBEROS_THREADS) -Jntlm.threads=$(NTLM_THREADS) $(EXTRA) \
	  -Jsummariser.interval=5 -l /results/$(RUN_ID)/results.jtl -j /results/$(RUN_ID)/jmeter.log -e -o /results/$(RUN_ID)/report
	@echo "Report: http://localhost:18080/results/$(RUN_ID)/report/index.html"

kind-ui: ## Open the web front end running in kind
	open http://localhost:18080

kind-down: ## Delete the kind cluster
	kind delete cluster --name $(KIND_CLUSTER)
