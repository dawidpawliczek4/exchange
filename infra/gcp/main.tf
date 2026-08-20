terraform {
  required_version = ">= 1.9"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }
}

variable "project_id" {
  type    = string
  default = "exchange-dawid-2026"
}

variable "region" {
  type    = string
  default = "europe-central2"
}

variable "zone" {
  type    = string
  default = "europe-central2-a"
}

provider "google" {
  project = var.project_id
  region  = var.region
}

resource "google_artifact_registry_repository" "exchange" {
  location      = var.region
  repository_id = "exchange"
  format        = "DOCKER"
  description   = "exchange images"
}

resource "google_container_cluster" "exchange" {
  name     = "exchange"
  location = var.zone

  remove_default_node_pool = true
  initial_node_count       = 1

  deletion_protection = false
}

resource "google_container_node_pool" "default" {
  name       = "default"
  location   = var.zone
  cluster    = google_container_cluster.exchange.name
  node_count = 3

  node_config {
    machine_type = "e2-medium"
    disk_size_gb = 30
    oauth_scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }
}

output "registry_url" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.exchange.repository_id}"
}

output "cluster_name" {
  value = google_container_cluster.exchange.name
}

output "get_credentials_command" {
  value = "gcloud container clusters get-credentials ${google_container_cluster.exchange.name} --zone ${var.zone} --project ${var.project_id}"
}
