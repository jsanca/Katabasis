# Agent Reviews

## Purpose

Store durable review records that are not implementation-completion reports.

## What belongs here

Architecture, boundary, verification, security, adversarial, and documentation review records, named `<task-id>-<lowercase-slug>.md` when possible. A task-addressable evidence package may live beneath `<task-id>/qa-evidence/<execution-id>/` when deterministic execution output must be retained for later review. Its summary records scope, results, provenance, limitations, and bounded raw evidence links; it is evidence for review, not a review verdict.

## Evidence promotion and CI retention

The accepted/canonical evidence for a task is identified by an exact timestamped package link in that task's report or review. That link is the promotion decision: the selected package is committed with the accepted work and remains on the main branch. Do not maintain a mutable `latest` marker or infer acceptance from a directory name.

The same `scripts/verify.sh` command can write a package to this hierarchy locally or to a caller-provided directory in CI. CI uploads its output as a transient artifact; it is not automatically committed. An engineer or reviewer chooses whether a specific CI or local package materially supports acceptance, then includes or references that exact package in the task's durable records.

## What does not belong here

Implementation completion reports or open recovery state. Link material reviews from `../../ENGINEERING_LOG.md`.
