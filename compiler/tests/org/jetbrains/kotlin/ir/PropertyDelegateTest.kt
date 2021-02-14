/*
* Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
* Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
*/

package org.jetbrains.kotlin.ir

import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.backend.jvm.JvmIrCodegenFactory
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.codegen.GenerationUtils.compileFiles
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.types.impl.IrTypeBase
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.test.KotlinTestUtils
import java.io.File


class PropertyDelegateTest : AbstractIrTextTestCase() {

    private var generationState: GenerationState? = null
    private val kotlinLikeDumpOptions = KotlinLikeDumpOptions(
        printRegionsPerFile = true,
        printFileName = true,
        printFilePath = true,
        useNamedArguments = true,
        labelPrintingStrategy = LabelPrintingStrategy.ALWAYS,
        printFakeOverridesStrategy = FakeOverridesStrategy.ALL
    )

    override fun setupEnvironment(environment: KotlinCoreEnvironment) {
        super.setupEnvironment(environment)
//        val mockJdk = listOf(File(KotlinTestUtils.getHomeDirectory(), "compiler/testData/mockJDK/jre/lib/rt.jar"))
//        environment.registerJavac(bootClasspath = mockJdk)
//        environment.configuration.put(JVMConfigurationKeys.USE_JAVAC, true)
    }

    override fun doTest(wholeFile: File, testFiles: List<TestFile>) {
//        val mockJdk = listOf(File(KotlinTestUtils.getHomeDirectory(), "compiler/testData/mockJDK/jre/lib/rt.jar"))
//        myEnvironment.registerJavac(bootClasspath = mockJdk)
//        myEnvironment.configuration.put(JVMConfigurationKeys.USE_JAVAC, true)
        myEnvironment.configuration.put(JVMConfigurationKeys.IR, true)

        generationState = compileFiles(
            myFiles.psiFiles, myEnvironment, classBuilderFactory,
            NoScopeRecordCliBindingTrace()
        )

        val irModuleLower = (generationState?.codegenFactory as? JvmIrCodegenFactory)?.irModule

        val before = generateIrModule(false).dumpKotlinLike(kotlinLikeDumpOptions)
        val after = irModuleLower?.dumpKotlinLike(kotlinLikeDumpOptions) ?: ""

//        println(irModuleLower?.dumpKotlinLike(kotlinLikeDumpOptions))


        FileUtil.writeToFile(
            File(wholeFile.parentFile.path, "before.kt"),
            before
        )
        FileUtil.writeToFile(
            File(wholeFile.parentFile.path, "after.kt"),
            after
        )
    }


    fun testFile() {
        KotlinTestUtils.runTest(this::doTest, this, "compiler/testData/ir/irOptimization/test.kt")
    }

    fun testFakeOverride() {
        KotlinTestUtils.runTest(this::doTest, this, "compiler/testData/ir/irOptimization/fakeOverride.kt")
    }

    fun testDelegateToAnother() {
        KotlinTestUtils.runTest(this::doTest, this, "compiler/testData/ir/irOptimization/delegateToAnother.kt")
    }

    fun testDelegate() {
        KotlinTestUtils.runTest(this::doTest, this, "compiler/testData/ir/irOptimization/delegate.kt")
    }

    fun testTwoClasses() {
        KotlinTestUtils.runTest(this::doTest, this, "compiler/testData/ir/irOptimization/twoClasses.kt")
    }
}

private class DebugVisitor(val withType: Boolean = false) : IrElementVisitorVoid {
    private var deep = 0

    private fun deepPrint(str: String) {
        println("    ".repeat(deep) + str)
    }

    override fun visitElement(element: IrElement) {
        deepPrint(element::class.simpleName.toString())
        acceptChildrenWithDeep(element)
    }

    override fun visitConstructorCall(expression: IrConstructorCall) {
        super.visitConstructorCall(expression)
    }

    override fun <T> visitConst(expression: IrConst<T>) {
        deepPrint((if (withType) "Const: " else "") + expression.value.toString())
        acceptChildrenWithDeep(expression)
    }

    override fun visitClass(declaration: IrClass) {
        val str =
            declaration.kind.toString() + " " + declaration.thisReceiver?.toPString() + ": " + declaration.typeParameters.joinToString { it.toString() }
        deepPrint(str)
        deep += 1
        declaration.declarations.forEach { it.accept(this, null) }
        deep -= 1
    }

    override fun visitConstructor(declaration: IrConstructor) {
        val str = "constructor  " + "(" + declaration.dispatchReceiverParameter?.toPString() + ")|" +
                declaration.extensionReceiverParameter?.toPString() + " " +
                declaration.valueParameters.joinToString(prefix = "(", postfix = ")") { it.toPString() }
        deepPrint(str)
        deep += 1
        declaration.body?.accept(this, null)
        deep -= 1
    }

    override fun visitFunction(declaration: IrFunction) {
        if (declaration.isPropertyAccessor) {
            declaration as IrSimpleFunction
            deepPrint(declaration.correspondingPropertySymbol.toString())
        } else {

            /*

        dispatchReceiverParameter?.accept(visitor, data)
        extensionReceiverParameter?.accept(visitor, data)
        valueParameters.forEach { it.accept(visitor, data) }

        body?.accept(visitor, data)
             */
            val str = "fun " + "(" + declaration.dispatchReceiverParameter?.toPString() + ")|" +
//                    declaration.extensionReceiverParameter?.toPString() + " " +
                    declaration.name.identifier + " " +
                    declaration.valueParameters.joinToString(prefix = "(", postfix = ")") { it.toPString() }
            deepPrint(str)
        }
        deep += 1
        declaration.body?.accept(this, null)
        deep -= 1
    }

    override fun visitProperty(declaration: IrProperty) {
        super.visitProperty(declaration)
    }

    override fun visitValueParameter(declaration: IrValueParameter) {
        deepPrint((if (withType) "ValueParameter: " else "") + declaration.toPString())
        acceptChildrenWithDeep(declaration)
    }


    override fun visitInstanceInitializerCall(expression: IrInstanceInitializerCall) {
        super.visitInstanceInitializerCall(expression)
    }

    private fun acceptChildrenWithDeep(element: IrElement) {
        deep += 1
        element.acceptChildrenVoid(this)
        deep -= 1
    }
}

private fun IrValueParameter.toPString(): String {
    return "${name}: ${(type as IrTypeBase).kotlinType}"
}

