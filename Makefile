.PHONY: k8s-up k8s-status k8s-down
k8s-up:
	bash infra/k8s/kind-up.sh
k8s-status:
	kubectl -n traffic-platform get pods,svc,hpa,ingress
k8s-down:
	bash infra/k8s/kind-down.sh
