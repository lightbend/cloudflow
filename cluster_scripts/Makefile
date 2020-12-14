
.PHONY: wipe-gke-clusters
wipe--gke-clusters:
	@echo '****** Wipe clusters and disks'
	gcloud container clusters list --format="table[no-heading](name)" | xargs -n 1 ./delete-cluster-gke.sh
