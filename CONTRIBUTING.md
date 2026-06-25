[中文](./CONTRIBUTING-zh.md) | English

## How to Contribute

Before contributing code, please take a moment to understand the process of contributing to Spring AI Alibaba DeepResearch.

### What to Contribute?

We welcome any contribution, whether it's a simple typo correction, bug fix, or adding new features. Please actively ask questions or submit PRs. We also value documentation and integration with other open-source projects, and welcome contributions in these areas.

For more complex modifications, we suggest adding a Feature tag to the Issue and briefly describing the design and changes.

### Forking the Repository and Cloneing it Locally

- Click the `Fork` icon in the upper right corner of [this project](https://github.com/spring-ai-alibaba/deepresearch) to fork alibaba/spring-ai-alibaba to your own hosting space.

- Clone the spring-ai-alibaba repository from your own account to your local machine. For example, for my account `chickenlj`, this would be done by executing `git clone https://github.com/spring-ai-alibaba/deepresearch`.

### Configuring Github Information

- On your machine, execute `git config --list` to view the global username and email address for Git.

- Check if the displayed user.name and user.email match your GitHub username and email address.

- If your company uses its own GitLab or you are using another commercial GitLab, mismatches may occur. In this case, you need to set a separate username and email address for the spring-ai-alibaba project.

- For instructions on setting your username and email address, please refer to the official GitHub documentation: [Setting your username](https://help.github.com/articles/setting-your-username-in-git/#setting-your-git-username-for-a-single-repository) and [Setting your email address](https://help.github.com/articles/setting-your-commit-email-address-in-git/).

### Merging the Latest Code

After forking the code, new commits may appear on the original repository's main branch. To avoid conflicts between the PR and commits in Master, you need to merge the main branch promptly.

- In your local `spring-ai-alibaba` directory, execute `git remote add upstream https://github.com/spring-ai-alibaba/deepresearch` to add the original repository address to the remote stream.

- In your local `spring-ai-alibaba` directory, execute `git fetch upstream` to fetch the remote stream to your local machine.

- In your local `spring-ai-alibaba` directory, execute `git checkout main` to switch to the `master` branch.

- In your local `spring-ai-alibaba` directory, execute `git rebase upstream/main` to rebase the latest code.

### Configuring Spring AI Standard Code Format

As one of the implementations of Spring AI, Spring AI Alibaba directly adopts the Spring AI project specification for code style. Please refer to the relevant code style specification instructions before starting. You need to configure the code style specification before committing code.

### Development

Develop your own features. **After development, it is recommended to use the `mvn clean package` command to ensure that the modified code can be compiled successfully locally. Executing this command will also automatically format the code in Spring's way.** Then commit the code. Before committing, please remember to create a new branch related to this feature and use that branch to commit the code.

### Local CI

After completing local Boe environment development, it is strongly recommended to run the `make` command provided by the project's `CI\make` to perform local continuous integration (CI) checks before submitting a pull request, ensuring that the code conforms to the project's standards and specifications. If you have any questions about local CI, you can type `make help` in the console for specific information.

### Local Checkstyle

To reduce unnecessary code style issues, Spring AI Alibaba provides a local checkstyle check function. You can execute the `mvn checkstyle:check` command in the project root directory to check if the code style conforms to the specifications.

### Deleting Unused Imports

To ensure clean code, please delete unused imports in Java files. Unused imports can be automatically deleted by executing the `mvn spotless:apply` command.

### Submitting the Latest Code

After completing the coding, you need to format and check the commit message according to the PR specification `[lint-pr-title.yml](.github/workflows/lint-pr-title.yml)` to ensure that the commit message conforms to the specification.

Commit Specification: `git commit -m "type(module): space conforming commit message", for example `feat(docs): contribute-zh update`

### Merging the Latest Code

- Similarly, before submitting the PR, you need to rebase the code of the main branch (if your target branch is not the main branch, you need to rebase the corresponding target branch). Please refer to the previous chapters for specific steps.

- If conflicts occur, you need to resolve them first.

### Submitting the PR

Submit the PR, specifying the changes and implemented functions according to the `Pull request template`, and wait for code review and merging. Become a Spring AI Alibaba Contributor and contribute to making Spring AI Alibaba more usable.