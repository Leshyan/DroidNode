package com.actl.mvp.apiregistryksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

class ApiRegistrySymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return ApiRegistrySymbolProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger
        )
    }
}

class ApiRegistrySymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {
    private val generator = ApiRegistryGenerator(codeGenerator, logger)

    override fun process(resolver: com.google.devtools.ksp.processing.Resolver): List<com.google.devtools.ksp.symbol.KSAnnotated> {
        return generator.process(resolver)
    }
}
