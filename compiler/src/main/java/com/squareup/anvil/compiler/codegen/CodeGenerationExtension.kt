package com.squareup.anvil.compiler.codegen

import com.squareup.anvil.compiler.CommandLineOptions
import com.squareup.anvil.compiler.api.AnvilCompilationException
import com.squareup.anvil.compiler.api.CodeGenerator
import com.squareup.anvil.compiler.api.FileWithContent
import com.squareup.anvil.compiler.api.GeneratedFileWithSources
import com.squareup.anvil.compiler.codegen.incremental.AbsoluteFile
import com.squareup.anvil.compiler.codegen.incremental.FileCacheOperations
import com.squareup.anvil.compiler.codegen.incremental.GeneratedFileCache
import com.squareup.anvil.compiler.codegen.incremental.GeneratedFileCache.Companion.GENERATED_FILE_CACHE_NAME
import com.squareup.anvil.compiler.codegen.incremental.ProjectDir
import com.squareup.anvil.compiler.codegen.reference.RealAnvilModuleDescriptor
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.analyzer.AnalysisResult.RetryWithAdditionalRoots
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import java.io.File
import kotlin.LazyThreadSafetyMode.NONE

internal class CodeGenerationExtension(
  codeGenerators: List<CodeGenerator>,
  private val commandLineOptions: CommandLineOptions,
  private val moduleDescriptorFactory: RealAnvilModuleDescriptor.Factory,
  private val projectDir: ProjectDir,
  private val generatedDir: File,
  private val cacheDir: File,
  private val trackSourceFiles: Boolean,
) : AnalysisHandlerExtension {

  private val generatedFileCache by lazy(NONE) {
    val binaryFile = cacheDir.resolve(GENERATED_FILE_CACHE_NAME)
    GeneratedFileCache.fromFile(
      binaryFile = binaryFile,
      projectDir = projectDir,
    )
  }

  private val cacheOperations by lazy(NONE) {
    FileCacheOperations(
      cache = generatedFileCache,
      projectDir = projectDir,
    )
  }

  private var didRecompile = false

  private val codeGenerators = codeGenerators
    .onEach {
      check(it !is FlushingCodeGenerator || it !is PrivateCodeGenerator) {
        "A code generator can't be a private code generator and flushing code generator at the " +
          "same time. Private code generators don't impact other code generators, therefore " +
          "they shouldn't need to flush files after other code generators generated code."
      }
    }
    // Use a stable sort in case code generators depend on the order.
    // At least don't make it random.
    .sortedWith(compareBy({ it is PrivateCodeGenerator }, { it::class.qualifiedName }))

  override fun doAnalysis(
    project: Project,
    module: ModuleDescriptor,
    projectContext: ProjectContext,
    files: Collection<KtFile>,
    bindingTrace: BindingTrace,
    componentProvider: ComponentProvider,
  ): AnalysisResult? {
    // Tell the compiler that we have something to do in the analysisCompleted() method if
    // necessary.
    return if (!didRecompile) AnalysisResult.EMPTY else null
  }

  override fun analysisCompleted(
    project: Project,
    module: ModuleDescriptor,
    bindingTrace: BindingTrace,
    files: Collection<KtFile>,
  ): AnalysisResult? {
    if (didRecompile) return null
    didRecompile = true

    val filesAsAbsoluteFiles by lazy(NONE) {
      files.map { AbsoluteFile(File(it.virtualFilePath)) }
    }

    // For incremental compilation: restore any missing generated files from the cache,
    // and delete any generated files that are out-of-date due to source changes.
    if (trackSourceFiles) {
      cacheOperations.restoreFromCache(inputSourceFiles = filesAsAbsoluteFiles)
    }

    // OLD incremental behavior: just delete everything if we're not tracking source files
    if (!trackSourceFiles) {
      generatedDir.listFiles()?.forEach {
        check(it.deleteRecursively()) { "Could not clean file: $it" }
      }
    }

    // The files in `files` can include generated files.
    // Those files must exist when they're passed in to `anvilModule.addFiles(...)` to avoid:
    // https://youtrack.jetbrains.com/issue/KT-49340
    val psiManager = PsiManager.getInstance(project)
    val anvilModule = moduleDescriptorFactory.create(module)
    anvilModule.addFiles(files)

    // If there are no changed files,
    // don't parse or generate anything all over again for the same results.
    // We can't return null, but we can return an empty AnalysisResult.
    if (trackSourceFiles && files.isEmpty()) {
      return RetryWithAdditionalRoots(
        bindingContext = bindingTrace.bindingContext,
        moduleDescriptor = anvilModule,
        additionalJavaRoots = emptyList(),
        additionalKotlinRoots = listOf(generatedDir),
        addToEnvironment = true,
      )
    }

    val generatedFiles = generateCode(
      codeGenerators = codeGenerators,
      psiManager = psiManager,
      anvilModule = anvilModule,
      files = files,
    )

    if (trackSourceFiles) {
      // Add all generated files to the cache.
      cacheOperations.addToCache(
        sourceFiles = filesAsAbsoluteFiles,
        filesWithSources = generatedFiles.filterIsInstance<GeneratedFileWithSources>(),
      )
    }

    // This restarts the analysis phase and will include our files.
    return RetryWithAdditionalRoots(
      bindingContext = bindingTrace.bindingContext,
      moduleDescriptor = anvilModule,
      additionalJavaRoots = emptyList(),
      additionalKotlinRoots = generatedFiles.map { it.file },
      addToEnvironment = true,
    )
  }

  private fun generateCode(
    codeGenerators: List<CodeGenerator>,
    psiManager: PsiManager,
    anvilModule: RealAnvilModuleDescriptor,
    files: Collection<KtFile>,
  ): MutableCollection<FileWithContent> {

    val anvilContext = commandLineOptions.toAnvilContext(anvilModule)

    val generatedFiles = mutableMapOf<String, FileWithContent>()

    val (privateCodeGenerators, nonPrivateCodeGenerators) =
      codeGenerators
        .filter { it.isApplicable(anvilContext) }
        .partition { it is PrivateCodeGenerator }

    fun onGenerated(
      generatedFile: FileWithContent,
      codeGenerator: CodeGenerator,
      allowOverwrites: Boolean,
    ) {

      checkNoUntrackedSources(
        generatedFile = generatedFile,
        codeGenerator = codeGenerator,
      )
      val relativePath = generatedFile.file.relativeTo(generatedDir).path

      val alreadyGenerated = generatedFiles.put(relativePath, generatedFile)

      if (alreadyGenerated != null && !allowOverwrites) {
        checkNoOverwrites(
          alreadyGenerated = alreadyGenerated,
          generatedFile = generatedFile,
          codeGenerator = codeGenerator,
        )
      }
    }

    fun Collection<CodeGenerator>.generateCode(files: Collection<KtFile>): List<KtFile> =
      flatMap { codeGenerator ->
        codeGenerator.generateCode(generatedDir, anvilModule, files)
          .onEach {
            onGenerated(
              generatedFile = it,
              codeGenerator = codeGenerator,
              allowOverwrites = false,
            )
          }
          .toKtFiles(psiManager, anvilModule)
      }

    fun Collection<CodeGenerator>.flush(): List<KtFile> =
      filterIsInstance<FlushingCodeGenerator>()
        .flatMap { codeGenerator ->
          codeGenerator.flush(generatedDir, anvilModule)
            .onEach {
              onGenerated(
                generatedFile = it,
                codeGenerator = codeGenerator,
                // flushing code generators write the files but no content during normal rounds.
                allowOverwrites = true,
              )
            }
            .toKtFiles(psiManager, anvilModule)
        }

    var newFiles = nonPrivateCodeGenerators.generateCode(files)

    while (newFiles.isNotEmpty()) {
      // Parse the KtFile for each generated file. Then feed the code generators with the new
      // parsed files until no new files are generated.
      newFiles = nonPrivateCodeGenerators.generateCode(newFiles)
    }

    nonPrivateCodeGenerators.flush()

    // PrivateCodeGenerators don't impact other code generators. Therefore, they can be called a
    // single time at the end.
    privateCodeGenerators.generateCode(anvilModule.allFiles.toList())

    return generatedFiles.values
  }

  private fun Collection<FileWithContent>.toKtFiles(
    psiManager: PsiManager,
    anvilModule: RealAnvilModuleDescriptor,
  ): List<KtFile> = mapNotNull { (file, content) ->
    val virtualFile = LightVirtualFile(
      // This must stay an absolute path, or `psiManager.findFile(...)` won't be able to resolve it.
      file.path,
      KotlinFileType.INSTANCE,
      content,
    )

    psiManager.findFile(virtualFile)
  }
    .filterIsInstance<KtFile>()
    .also { anvilModule.addFiles(it) }

  private fun checkNoOverwrites(
    alreadyGenerated: FileWithContent,
    generatedFile: FileWithContent,
    codeGenerator: CodeGenerator,
  ) {

    if (alreadyGenerated.content != generatedFile.content) {
      throw AnvilCompilationException(
        """
        |There were duplicate generated files. Generating and overwriting the same file leads to unexpected results.
        |
        |The file was generated by: ${codeGenerator::class}
        |The file is: ${generatedFile.file.path}
        |
        |The content of the already generated file is:
        |
        |${alreadyGenerated.content.prependIndent("\t")}
        |
        |The content of the new file is:
        |
        |${generatedFile.content.prependIndent("\t")}
        """.trimMargin(),
      )
    }
  }

  private fun checkNoUntrackedSources(
    generatedFile: FileWithContent,
    codeGenerator: CodeGenerator,
  ) {
    if (!trackSourceFiles) return

    if (generatedFile !is GeneratedFileWithSources) {
      throw AnvilCompilationException(
        """
        |Source file tracking is enabled but this generated file is not tracking them.
        |Please report this issue to the code generator's maintainers.
        |
        |The file was generated by: ${codeGenerator::class}
        |The file is: ${generatedFile.file.path}
        |
        |To stop this error, disable the `trackSourceFiles` property in the Anvil Gradle extension:
        |
        |   // build.gradle(.kts)
        |   anvil {
        |     trackSourceFiles = false
        |   }
        |
        |or disable the property in `gradle.properties`:
        |
        |   # gradle.properties
        |   com.squareup.anvil.trackSourceFiles=false
        |
        """.trimMargin(),
      )
    }
  }
}
