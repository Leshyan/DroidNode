package com.actl.mvp.apiregistryksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.Modifier
import java.io.OutputStreamWriter

internal class ApiRegistryGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) {
    private var generated = false

    fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        val modules = resolver.getAllFiles()
            .flatMap { file -> file.declarations.asSequence().flatMap(::walkDeclarations) }
            .filterIsInstance<KSClassDeclaration>()
            .filter(::isApiModule)
            .mapNotNull { it.qualifiedName?.asString() }
            .distinct()
            .sorted()
            .toList()

        val dependencies = Dependencies(
            aggregating = true,
            sources = resolver.getAllFiles().toList().toTypedArray()
        )
        val output = codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = GENERATED_PACKAGE,
            fileName = GENERATED_FILE
        )
        OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
            writer.appendLine("package $GENERATED_PACKAGE")
            writer.appendLine()
            writer.appendLine("import com.actl.mvp.api.framework.ApiDefinition")
            modules.forEach { fqcn -> writer.appendLine("import $fqcn") }
            writer.appendLine()
            writer.appendLine("object $GENERATED_FILE {")
            writer.appendLine("    fun modules(): List<ApiDefinition> = listOf(")
            modules.forEachIndexed { index, fqcn ->
                val simple = fqcn.substringAfterLast('.')
                val suffix = if (index == modules.lastIndex) "" else ","
                writer.appendLine("        $simple()$suffix")
            }
            writer.appendLine("    )")
            writer.appendLine("}")
        }

        logger.info("Generated $GENERATED_PACKAGE.$GENERATED_FILE with ${modules.size} module(s)")
        generated = true
        return emptyList()
    }

    private fun walkDeclarations(declaration: KSDeclaration): Sequence<KSDeclaration> = sequence {
        yield(declaration)
        if (declaration is KSClassDeclaration) {
            declaration.declarations.forEach { nested ->
                yieldAll(walkDeclarations(nested))
            }
        }
    }

    private fun isApiModule(declaration: KSClassDeclaration): Boolean {
        if (declaration.classKind != ClassKind.CLASS) return false
        if (Modifier.ABSTRACT in declaration.modifiers) return false
        if (Modifier.INNER in declaration.modifiers) return false

        val packageName = declaration.packageName.asString()
        if (!packageName.startsWith(API_PACKAGE_PREFIX)) return false
        if (packageName.startsWith(FRAMEWORK_PACKAGE_PREFIX)) return false

        return implementsApiDefinition(declaration, mutableSetOf())
    }

    private fun implementsApiDefinition(
        declaration: KSClassDeclaration,
        visited: MutableSet<String>
    ): Boolean {
        val selfName = declaration.qualifiedName?.asString() ?: return false
        if (!visited.add(selfName)) return false

        return declaration.superTypes.any { superType ->
            val superDecl = superType.resolve().declaration as? KSClassDeclaration ?: return@any false
            val superName = superDecl.qualifiedName?.asString()
            superName == API_DEFINITION_FQCN || implementsApiDefinition(superDecl, visited)
        }
    }

    private companion object {
        private const val API_PACKAGE_PREFIX = "com.actl.mvp.api."
        private const val FRAMEWORK_PACKAGE_PREFIX = "com.actl.mvp.api.framework."
        private const val API_DEFINITION_FQCN = "com.actl.mvp.api.framework.ApiDefinition"
        private const val GENERATED_PACKAGE = "com.actl.mvp.api.framework"
        private const val GENERATED_FILE = "GeneratedApiRegistry"
    }
}
