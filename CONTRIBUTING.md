# Welcome! Thank you for contributing to cloudflow!

We follow the standard GitHub [fork & pull](https://help.github.com/articles/using-pull-requests/#fork--pull) approach to pull requests. Just fork the official repo, develop in a branch, and submit a PR!

The [Cloudflow website](https://cloudflow.io) and the [documentation](https://cloudflow.io/docs) are maintained in a separate repository: 
https://github.com/lightbend/cloudflow-docs

You're always welcome to submit your PR straight away and start the discussion (without reading the rest of this wonderful doc, or the README.md). The goal of these notes is to make your experience contributing to cloudflow as smooth and pleasant as possible. We're happy to guide you through the process once you've submitted your PR.

# cloudflow contributing guidelines

These guidelines are meant to be a living document that should be changed and adapted as needed.
We encourage changes that make it easier to achieve our goals in an efficient way.

## General workflow

The steps below describe how to get a patch into a main development branch (e.g. `master`). 
The steps are exactly the same for everyone involved in the project (be it core team, or first time contributor).

1. To avoid duplicated effort, it might be good to check the [issue tracker](https://github.com/lightbend/cloudflow/issues) and [existing pull requests](https://github.com/lightbend/cloudflow/pulls) for existing work.
   - If there is no ticket yet, feel free to [create one](https://github.com/lightbend/cloudflow/issues/new) to discuss the problem and the approach you want to take to solve it.
1. [Fork the project](https://github.com/lightbend/cloudflow#fork-destination-box) on GitHub. You'll need to create a feature-branch for your work on your fork, as this way you'll be able to submit a pull request against the mainline cloudflow.
1. Create a branch on your fork and work on the feature. For example: `git checkout -b wip-add-kafka-streams-runtime`
   - Please make sure to follow the general quality guidelines (specified below) when developing your patch.
   - Please write additional tests covering your feature and adjust existing ones if needed before submitting your pull request.
1. Once your feature is complete, prepare the commit following our [Creating Commits And Writing Commit Messages](#creating-commits-and-writing-commit-messages). For example, a good commit message would be: `Adding compression support for Manifests #22222` (note the reference to the ticket it aimed to resolve).
1. If it's a new feature, or a change of behavior, document it on the [cloudflow-docs](https://github.com/lightbend/cloudflow/tree/master/cloudflow-docs), remember, an undocumented feature is not a feature. If the feature was touching Scala or Java DSL, make sure to document both the Scala and Java APIs.
1. Now it's finally time to [submit the pull request](https://help.github.com/articles/using-pull-requests)!
    - Please make sure to include a reference to the issue you're solving *in the comment* for the Pull Request, this will cause the PR to be linked properly with the Issue. Examples of good phrases for this are: "Resolves #1234" or "Refs #1234".
1. If you have not already done so, you will be asked by our CLA bot to [sign the Lightbend CLA](http://www.lightbend.com/contribute/cla) online. CLA stands for Contributor License Agreement and is a way of protecting intellectual property disputes from harming the project.
1. If you're not already on the contributors white-list, the @cloudflow-ci bot will ask `Can one of the repo owners verify this patch?`, to which a core member will reply by commenting `OK TO TEST`. This is just a sanity check to prevent malicious code from being run on the Jenkins cluster.
1. Now both committers and interested people will review your code. This process is to ensure the code we merge is of the best possible quality, and that no silly mistakes slip through. You're expected to follow-up these comments by adding new commits to the same branch. The commit messages of those commits can be more loose, for example: `Removed debugging using printline`, as they all will be squashed into one commit before merging into the main branch.
    - The community and team are really nice people, so don't be afraid to ask follow up questions if you didn't understand some comment, or would like clarification on how to continue with a given feature. We're here to help, so feel free to ask and discuss any kind of questions you might have during review!
1. After the review you should fix the issues as needed (pushing a new commit for new review etc.), iterating until the reviewers give their thumbs up–which is signalled usually by a comment saying `LGTM`, which means "Looks Good To Me". 
    - In general a PR is expected to get 2 LGTMs from the team before it is merged. If the PR is trivial, or under special circumstances (such as most of the team being on vacation, a PR was very thoroughly reviewed/tested and surely is correct) one LGTM may be fine as well.
1. If the code change needs to be applied to other branches as well (for example a bugfix needing to be backported to a previous version), one of the team will either ask you to submit a PR with the same commit to the old branch, or do this for you.
   - Follow the [backporting steps](#backporting) below.
1. Once everything is said and done, your pull request gets merged :tada: Your feature will be available with the next “earliest” release milestone (i.e. if back-ported so that it will be in release x.y.z, find the relevant milestone for that release). And of course you will be given credit for the fix in the release stats during the release's announcement. You've made it!

The TL;DR; of the above very precise workflow version is:

1. Fork cloudflow
2. Hack and test on your feature (on a branch)
3. Document it 
4. Submit a PR
5. Sign the CLA if necessary
6. Keep polishing it until received enough LGTM
7. Profit!

## Pull request requirements

For a pull request to be considered at all it has to meet these requirements:

1. Regardless if the code introduces new features or fixes bugs or regressions, it must have comprehensive tests.
1. The code must be well documented in the Lightbend's standard documentation format (see the ‘Documentation’ section below).
1. The commit messages must properly describe the changes, see further below.
1. A pull request must indicate (link to) the issue it is aimed to resolve in the description (or comments) of the PR, in order to establish a link between PR and Issue. This can be achieved by writing "Fixes #1234" or similar in PR description.
1. All Lightbend projects must include Lightbend copyright notices.  Each project can choose between one of two approaches:

    1. All source files in the project must have a Lightbend copyright notice in the file header.
    1. The Notices file for the project includes the Lightbend copyright notice and no other files contain copyright notices.  See http://www.apache.org/legal/src-headers.html for instructions for managing this approach for copyrights.

    cloudflow uses the first choice, having copyright notices in every file header.

### Additional guidelines

Some additional guidelines regarding source code are:

- Files should start with a ``Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>`` copyright header.
- Keep the code [DRY](http://programmer.97things.oreilly.com/wiki/index.php/Don%27t_Repeat_Yourself).
- Apply the [Boy Scout Rule](http://programmer.97things.oreilly.com/wiki/index.php/The_Boy_Scout_Rule) whenever you have the chance to.
- Never delete or change existing copyright notices, just add additional info.
- Do not use ``@author`` tags since it does not encourage [Collective Code Ownership](http://www.extremeprogramming.org/rules/collective.html).
  - Contributors , each project should make sure that the contributors gets the credit they deserve—in a text file or page on the project website and in the release notes etc.

If these requirements are not met then the code should **not** be merged into master, or even reviewed - regardless of how good or important it is. No exceptions.

Whether or not a pull request (or parts of it) shall be back- or forward-ported will be discussed on the pull request discussion page, it shall therefore not be part of the commit messages. If desired the intent can be expressed in the pull request description.

## Documentation

All documentation is preferred to be in [Asciidoc](https://asciidoc.org/) format, which among other things allows all code in the documentation to be externalized into compiled files and imported into the documentation.

## External dependencies

All the external runtime dependencies for the project, including transitive dependencies, must have an open source license that is equal to, or compatible with, [Apache 2](http://www.apache.org/licenses/LICENSE-2.0).

This must be ensured by manually verifying the license for all the dependencies for the project:

1. Whenever a committer to the project changes a version of a dependency (including Scala) in the build file.
2. Whenever a committer to the project adds a new dependency.
3. Whenever a new release is cut (public or private for a customer).

Which licenses are compatible with Apache 2 are defined in [this doc](http://www.apache.org/legal/3party.html#category-a), where you can see that the licenses that are listed under ``Category A`` are automatically compatible with Apache 2, while the ones listed under ``Category B`` need additional action:

> Each license in this category requires some degree of [reciprocity](http://www.apache.org/legal/3party.html#define-reciprocal); therefore, additional action must be taken in order to minimize the chance that a user of an Apache product will create a derivative work of a reciprocally-licensed portion of an Apache product without being aware of the applicable requirements.

Each project must also create and maintain a list of all dependencies and their licenses, including all their transitive dependencies. This can be done either in the documentation or in the build file next to each dependency.

## Creating commits and writing commit messages

Follow these guidelines when creating public commits and writing commit messages.

1. If your work spans multiple local commits (for example; if you do safe point commits while working in a feature branch or work in a branch for a long time doing merges/rebases etc.) then please do not commit it all but rewrite the history by squashing the commits into a single big commit which you write a good commit message for (like discussed in the following sections). For more info read this article: [Git Workflow](http://sandofsky.com/blog/git-workflow.html). Every commit should be able to be used in isolation, cherry picked etc.

2. The first line should be a descriptive sentence what the commit is doing, including the ticket number. It should be possible to fully understand what the commit does—but not necessarily how it does it—by just reading this single line. We follow the “imperative present tense” style for commit messages ([more info here](http://tbaggery.com/2008/04/19/a-note-about-git-commit-messages.html)).

   It is **not ok** to only list the ticket number, type "minor fix" or similar.
   If the commit is a small fix, then you are done. If not, go to 3.

3. Following the single line description should be a blank line followed by an enumerated list with the details of the commit.

4. You can request review by a specific team member  for your commit (depending on the degree of automation we reach, the list may change over time):
    * ``Review by @gituser`` - if you want to notify someone on the team. The others can, and are encouraged to participate.

Example:

    enable Travis CI #1

    * Details 1
    * Details 2
    * Details 3

## Source style

Sometimes it is convenient to place 'internal' classes in their own package.
In such situations we prefer 'internal' over 'impl' as a package name.

### Scala style 

cloudflow uses [Scalafmt](https://scalameta.org/scalafmt/docs/installation.html) to enforce some of the code style rules.

It's recommended to enable Scalafmt formatting in IntelliJ. Use version 2019.1 or later. In
Preferences > Editor > Code Style > Scala, select Scalafmt as formatter and enable "Reformat on file save".
IntelliJ will then use the same settings and version as defined in `.scalafmt.conf` file. Then it's
not needed to use `sbt scalafmtAll` when editing with IntelliJ.

### Java style

Java code is currently not automatically reformatted by sbt (expecting to have a plugin to do this soon).
Thus we ask Java contributions to follow these simple guidelines:

- 2 spaces
- `{` on same line as method name
- in all other aspects, follow the [Oracle Java Style Guide](http://www.oracle.com/technetwork/java/codeconvtoc-136057.html)
