# How to Contribute

We'd love to accept your patches and contributions to this project. There are
just a few small guidelines you need to follow.

## Contributor License Agreement

Contributions to this project must be accompanied by a Contributor License
Agreement. You (or your employer) retain the copyright to your contribution;
this simply gives us permission to use and redistribute your contributions as
part of the project. Head over to <https://cla.developers.google.com/> to see
your current agreements on file or to sign a new one.

You generally only need to submit a CLA once, so if you've already submitted one
(even if it was for a different project), you probably don't need to do it
again.

## Code Reviews

All submissions, including submissions by project members, require review. We
use GitHub pull requests for this purpose. Consult
[GitHub Help](https://help.github.com/articles/about-pull-requests/) for more
information on using pull requests.

## Community Guidelines

This project follows [Google's Open Source Community
Guidelines](https://opensource.google/conduct/).

## Testing

1. Set the environment variables. You can see an example of the environment
variables needed by running `cat .envrc.example`. The instance in
`ALLOYDB_INSTANCE_NAME` must have public IP enabled.
1. Run `gcloud auth application-default login`
1. Run the tests:

   ```bash
   # Unit tests. No AlloyDB instance needed.
   ./scripts/test_units.sh

   # Integration tests from outside the instance's VPC. Connection tests use
   # public IP; tests that need private IP, PSC, or a direct connection are
   # skipped.
   ./scripts/test_system.sh --skip-private-ip

   # Full integration suite. Run this in the same VPC as the AlloyDB instance.
   ./scripts/test_system.sh
   ```
