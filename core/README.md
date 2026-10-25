# Shared infrastructure admission policy

There is currently no reusable cross-domain infrastructure to package. This directory is a responsibility boundary, not a Maven module. Do not create an empty `smartscity-core` or move ID-specific algorithms here. Add a shared artifact only when implemented components demonstrate a common requirement, and depend on it only where used.
