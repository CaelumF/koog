# Koog Documentation

This module contains documentation for the Koog framework, including user guides, API references, prompting guidelines, and other static files.

## Module Structure

The docs module is organized as follows:

- **docs/** - Contains markdown files with user documentation
- **overrides/** - Custom overrides for the MkDocs theme
- **prompt/** - Prompting guidelines with extensions for popular modules
- **src/** - Knit generated source code from documentation code snippets, should not be commited

## Documentation System

### MkDocs

The documentation is built using [MkDocs](https://www.mkdocs.org/) with the Material theme. The configuration is defined in `mkdocs.yml`, which specifies:

- Navigation structure
- Theme configuration
- Markdown extensions
- Repository links

The documentation is available at [https://docs.koog.ai/](https://docs.koog.ai/).

### Docs Code Snippets Verification

To ensure code snippets in documentation are compilable and up-to-date with the latest framework version, the [kotlinx-knit](https://github.com/Kotlin/kotlinx-knit) library is used.

Knit provides a Gradle plugin that extracts specially annotated Kotlin code snippets from markdown files and generates Kotlin source files.

#### How to fix docs?
1. Run `:docs:knitAssemble` task which will clean old knit-generated files, extract fresh code snippets to /src/main/kotlin and assemble the docs project:
```
./gradlew :docs:knitAssemble
```
2. Navigate to the file with the compilation error `example-[md-file-name]-[index].kt`
3. Fix the error in this file
4. Navigate to the code snippet in Markdown `md-file-name.md` by searing `<!--- KNIT example-[md-file-name]-[index].kt` -->`
5. Update the code snippet to reflect the changes in kt file
   * Update dependencies (usually they are provided in `<!--- INCLUDE -->` section)
   * Edit code (don't forget about tabulation when you just copy paste from kt)

#### How to annotate docs?

To annotate new Kotlin code snippets in Markdown and make them compilable:
1. Put an example annotation comment (`<!--- KNIT example-[md-file-name]-01.kt -->`) after every code block. 
It's not obligated to put right inexes, just set the `01` for each example, and they will be updated automatically after first knit run
```
    ```kotlin
    val agent = AIAgent(...)
    ```
    <!--- KNIT example-[md-file-name]-01.kt -->
```
2. In case you need some imports, add include comment (`<!--- INCLIDE ... -->`)

```
    <!--- INCLUDE
    import ai.koog.agents.core.agent.AIAgent
    -->
    ```kotlin
    val agent = AIAgent(...)
    ```
    <!--- KNIT example-[md-file-name]-01.kt -->
```
3. In case you need to whap your code into `main` or other function imports, 
use include comment (`<!--- INCLIDE ... -->`) for prefix and suffix comment (`<!--- SUFFIX ... -->`) for suffix
```
    <!--- INCLUDE
    import ai.koog.agents.core.agent.AIAgent
    fun main() {
    -->
    <!--- SUFFIX
    }
    -->
    ```kotlin
    val agent = AIAgent(...)
    ```
    <!--- KNIT example-[md-file-name]-01.kt -->
```

For more information, follow the examples in the [kotlinx-knit](https://github.com/Kotlin/kotlinx-knit) repository 
or refer to already annotated code snippets in the documentation.

### API Documentation

API reference documentation is generated using [Dokka](https://github.com/Kotlin/dokka), a documentation engine for Kotlin. The API documentation is built with:

```
./gradlew dokkaGenerate
```

The generated API documentation is deployed to [https://api.koog.ai/](https://api.koog.ai/).

## Prompts

In the [prompt](./prompt) directory, prompting guidelines with extensions for popular modules are stored. These guidelines help users create effective prompts for different LLM models and use cases.
