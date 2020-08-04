/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.g2kts

import kastree.ast.ExtrasMap
import kastree.ast.Node
import org.jetbrains.kotlin.utils.addToStdlib.cast

class GradleToKotlin {
    val extrasMap = ExtrasMapImpl()

    private fun GNode.toStatement(): GStatement? {
        return when (this) {
            is GStatement -> this
            is GSwitchCase -> null
            is GArgument -> null
            is GArgumentsList -> null
            is GExpression -> this.toStatement()
            is GUnaryOperator -> null
            is GBinaryOperator -> null
            is GDeclaration -> this.toStatement()
            is GProject -> null

        }
    }

    private fun List<GStatement>.toKotlin(): List<Node.Stmt> {
        val res = mutableListOf<Node.Stmt>()
        val bufComments: MutableList<Node.Extra> = mutableListOf()
        for (stmt in this) {
            if (bufComments.isNotEmpty() && res.isNotEmpty()) {
                bufComments.forEach {
                    extrasMap.addExtraBefore(res.last(), it)
                }
                bufComments.clear()
            }
            when (stmt) {
                is GComment -> {
//                    bufComments.add(Node.Extra.Comment(stmt.string, startsLine = true, endsLine = true))
                    if (res.isEmpty()) {
                        bufComments.add(Node.Extra.Comment(stmt.string, startsLine = true, endsLine = true))
                    } else {
                        extrasMap.addExtraAfter(
                            res.last(),
                            Node.Extra.Comment(stmt.string, startsLine = false, endsLine = false)
                        )
                    }
                }
                is GBlock -> res.add(stmt.toKotlin() as Node.Stmt)
                is GStatement.GExpr, is GStatement.GDecl -> {
                    var buf = when (stmt) {
                        is GStatement.GExpr -> stmt.expr.toKotlin()
                        is GStatement.GDecl -> stmt.decl.toKotlin()
                        else -> unreachable()
                    }
                    buf = when (buf) {
                        is Node.Expr -> Node.Stmt.Expr(buf)
                        is Node.Decl -> Node.Stmt.Decl(buf)
                        else -> buf
                    }

                    res.add(buf as Node.Stmt)
                }
//                is GStatement.GDecl -> res.add(Node.Stmt.Decl(stmt.decl.toKotlin() as Node.Decl))
            }
        }
        return res
    }

    fun GNode.toKotlin(): Node = when (this) {
        is GComment -> unreachable()
        is GProject -> Node.Block(statements.toKotlin())
        is GBlock -> Node.Block(statements.toKotlin())
        is GBrace -> Node.Expr.Brace(emptyList(), block?.toKotlin()?.cast())
        is GWhile -> Node.Expr.While(
            condition.toKotlin().cast(),
            body.toKotlin().cast(),
            false
        )
        is GStatement -> {
            val res = when (this) {
                is GStatement.GExpr -> expr.toKotlin()
                is GStatement.GDecl -> decl.toKotlin()
                is GBlock -> toKotlin()
                is GComment -> toKotlin()
            }
            when (res) {
                is Node.Expr -> Node.Stmt.Expr(res)
                is Node.Decl -> Node.Stmt.Decl(res)
                else -> res
            }
        }
        is GArgumentsList -> TODO()
        is GIdentifier -> Node.Expr.Name(name)
        is GMethodCall -> {
            val expr: Node.Expr = when (obj) {
                null -> method.toKotlin().cast()
                else -> obj!!.toKotlin().cast<Node.Expr>() dot method.toKotlin().cast()
            }
//        val lambda = when (this) {
//            is GConfigurationBlock -> lambda(closure.toKotlin().cast())
//            else -> null
//        }
            val lambda = closure?.let { lambda(it.toKotlin().cast()) }
            Node.Expr.Call(expr, emptyList(), arguments.args.map { it.toKotlin() as Node.ValueArg }, lambda)
        }
        is GClosure -> Node.Expr.Brace(emptyList(), statements.toKotlin().cast())
        is GTaskCreating -> {
            val lambda = body?.let { lambda(it.toKotlin().cast()) }
            property(
                vars = listOf(Node.Decl.Property.Var(name, null)),
                delegated = true,
                expr = name("tasks") dot call(name("creating"), lambda = lambda)
            )
        }
        is GConst -> Node.Expr.Const(text, Node.Expr.Const.Form.valueOf(type.toString()))
        is GString -> Node.Expr.StringTmpl(listOf(Node.Expr.StringTmpl.Elem.Regular(str)), false)
        is GBinaryExpression -> Node.Expr.BinaryOp(left.toKotlin().cast(), operator.toKotlin().cast(), right.toKotlin().cast())
        is GSimplePropertyAccess ->
            obj?.toKotlin()?.cast<Node.Expr>()?.dot(property.toKotlin().cast()) ?: property.toKotlin()
        is GExtensionAccess -> Node.Expr.ArrayAccess(obj!!.toKotlin().cast(), listOf(property.toKotlin().cast()))
        is GBinaryOperator.Common -> Node.Expr.BinaryOp.Oper.Token(Node.Expr.BinaryOp.Token.values().find { it.str == token.text } ?: error(
            ""
        ))
        is GBinaryOperator.Uncommon -> Node.Expr.BinaryOp.Oper.Infix(text)
        is GUnaryExpression -> Node.Expr.UnaryOp(expr.toKotlin().cast(), operator.toKotlin().cast(), prefix)
        is GUnaryOperator -> Node.Expr.UnaryOp.Oper(Node.Expr.UnaryOp.Token.values().find { it.str == token.text } ?: error(""))
        is GArgument -> Node.ValueArg(name, false, expr.toKotlin().cast())
        is GList -> Node.Expr.Call(
            Node.Expr.Name("listOf"),
            emptyList(),
            initializers.map { Node.ValueArg(null, false, it.toKotlin() as Node.Expr) },
            null
        )
        is GTaskAccess -> Node.Expr.BinaryOp(
            Node.Expr.Name("tasks"),
            Node.Expr.BinaryOp.Oper.Token(Node.Expr.BinaryOp.Token.DOT),
            Node.Expr.Call(
                Node.Expr.Name("named"),
                listOf(Node.Type(emptyList(), Node.TypeRef.Simple(listOf(Node.TypeRef.Simple.Piece(type ?: "Task", emptyList()))))),
                listOf(Node.ValueArg(null, false, Node.Expr.StringTmpl(listOf(Node.Expr.StringTmpl.Elem.Regular(task)), false))),
                if (this is GTaskConfigure) lambda(configure.toKotlin().cast()) else null
            )
        )
        is GBuildScriptBlock -> call(
            expr = name(type.text),
            lambda = lambda(block.toKotlin().cast())
        )
        is GVariableDeclaration -> {
            property(
                vars = listOf(
                    Node.Decl.Property.Var(
                        name.name,
                        type?.let {
                            Node.Type(
                                emptyList(),
                                Node.TypeRef.Nullable(Node.TypeRef.Simple(listOf(Node.TypeRef.Simple.Piece(it, emptyList()))))
                            )
                        })
                ),
                expr = expr?.toKotlin()?.cast(),
                readOnly = false
            )
        }
        is GIf -> Node.Expr.If(condition.toKotlin().cast(), body.toKotlin().cast(), elseBody?.toKotlin()?.cast())
        is GTryCatch -> Node.Expr.Try(
            body.toKotlin().cast(),
            catches.map {
                Node.Expr.Try.Catch(
                    emptyList(),
                    it.name,
                    Node.TypeRef.Simple(listOf(Node.TypeRef.Simple.Piece(it.type, emptyList()))),
                    it.block.toKotlin().cast()
                )
            },
            finallyBody?.toKotlin()?.cast()
        )
        is GSwitchCase -> Node.Expr.When.Entry(
            listOf(Node.Expr.When.Cond.Expr(expr.toKotlin().cast())),
            body.toKotlin().cast()
        )
        is GSwitch -> {
            val entries = cases.map { it.toKotlin() as Node.Expr.When.Entry }
            Node.Expr.When(
                expr.toKotlin().cast(),
                if (default == null) entries else entries.plus(default!!.toKotlin() as Node.Expr.When.Entry)
            )
        }
    }


}

class ExtrasMapImpl : ExtrasMap {
    private val extras: MutableList<Node.Extra> = mutableListOf()

    private val extrasAfter: MutableMap<Node, MutableList<Node.Extra>> = mutableMapOf()
    private val extrasBefore: MutableMap<Node, MutableList<Node.Extra>> = mutableMapOf()
    private val extrasWithin: MutableMap<Node, MutableList<Node.Extra>> = mutableMapOf()

    override fun extrasAfter(v: Node): List<Node.Extra> {
        return extrasAfter[v]?.toList() ?: return emptyList()
    }

    override fun extrasBefore(v: Node): List<Node.Extra> {
        return extrasBefore[v]?.toList() ?: return emptyList()
    }

    override fun extrasWithin(v: Node): List<Node.Extra> {
        return extrasWithin[v]?.toList() ?: return emptyList()
    }

    fun addExtraAfter(v: Node, extra: Node.Extra) {
        val list = extrasAfter[v]
        if (list == null) {
            extrasAfter[v] = mutableListOf(extra)
        } else {
            list.add(extra)
        }
//        extrasAfter.getOrDefault(v, mutableListOf()).add(extra)
    }

    fun addExtraBefore(v: Node, extra: Node.Extra) {
        val list = extrasBefore[v]
        if (list == null) {
            extrasBefore[v] = mutableListOf(extra)
        } else {
            list.add(extra)
        }
//        extrasBefore.getOrDefault(v, mutableListOf()).add(extra)
    }

    fun addExtraWithin(v: Node, extra: Node.Extra) {
        val list = extrasWithin[v]
        if (list == null) {
            extrasWithin[v] = mutableListOf(extra)
        } else {
            list.add(extra)
        }
//        extrasWithin.getOrDefault(v, mutableListOf()).add(extra)
    }
}

//fun GNode.toKotlin(): Node = when (this) {
//    is GComment -> TODO("add comment to struct")
//    is GProject -> Node.Block(statements.map { it.toKotlin() as Node.Stmt })
//    is GBlock -> Node.Block(statements.map { it.toKotlin() as Node.Stmt })
//    is GBrace -> Node.Expr.Brace(emptyList(), block?.toKotlin()?.cast())
//    is GWhile -> Node.Expr.While(
//        condition.toKotlin().cast(),
//        body.toKotlin().cast(),
//        false
//    )
//    is GStatement -> {
//        val res = when (this) {
//            is GStatement.GExpr -> expr.toKotlin()
//            is GStatement.GDecl -> decl.toKotlin()
//            is GBlock -> toKotlin()
//            is GComment -> toKotlin()
//        }
//        when (res) {
//            is Node.Expr -> Node.Stmt.Expr(res)
//            is Node.Decl -> Node.Stmt.Decl(res)
//            else -> res
//        }
//    }
//    is GArgumentsList -> TODO()
//    is GIdentifier -> Node.Expr.Name(name)
//    is GMethodCall -> {
//        val expr: Node.Expr = when (obj) {
//            null -> method.toKotlin().cast()
//            else -> obj!!.toKotlin().cast<Node.Expr>() dot method.toKotlin().cast()
//        }
////        val lambda = when (this) {
////            is GConfigurationBlock -> lambda(closure.toKotlin().cast())
////            else -> null
////        }
//        val lambda = closure?.let { lambda(it.toKotlin().cast()) }
//        Node.Expr.Call(expr, emptyList(), arguments.args.map { it.toKotlin() as Node.ValueArg }, lambda)
//    }
//    is GClosure -> Node.Expr.Brace(emptyList(), statements.toKotlin().cast())
//    is GTaskCreating -> {
//        val lambda = body?.let { lambda(it.toKotlin().cast()) }
//        property(
//            vars = listOf(Node.Decl.Property.Var(name, null)),
//            delegated = true,
//            expr = name("tasks") dot call(name("creating"), lambda = lambda)
//        )
//    }
//    is GConst -> Node.Expr.Const(text, Node.Expr.Const.Form.valueOf(type.toString()))
//    is GString -> Node.Expr.StringTmpl(listOf(Node.Expr.StringTmpl.Elem.Regular(str)), false)
//    is GBinaryExpression -> Node.Expr.BinaryOp(left.toKotlin().cast(), operator.toKotlin().cast(), right.toKotlin().cast())
//    is GSimplePropertyAccess ->
//        obj?.toKotlin()?.cast<Node.Expr>()?.dot(property.toKotlin().cast()) ?: property.toKotlin()
//    is GExtensionAccess -> Node.Expr.ArrayAccess(obj!!.toKotlin().cast(), listOf(property.toKotlin().cast()))
//    is GBinaryOperator.Common -> Node.Expr.BinaryOp.Oper.Token(Node.Expr.BinaryOp.Token.values().find { it.str == token.text } ?: error(""))
//    is GBinaryOperator.Uncommon -> Node.Expr.BinaryOp.Oper.Infix(text)
//    is GUnaryExpression -> Node.Expr.UnaryOp(expr.toKotlin().cast(), operator.toKotlin().cast(), prefix)
//    is GUnaryOperator -> Node.Expr.UnaryOp.Oper(Node.Expr.UnaryOp.Token.values().find { it.str == token.text } ?: error(""))
//    is GArgument -> Node.ValueArg(name, false, expr.toKotlin().cast())
//    is GList -> Node.Expr.Call(
//        Node.Expr.Name("listOf"),
//        emptyList(),
//        initializers.map { Node.ValueArg(null, false, it.toKotlin() as Node.Expr) },
//        null
//    )
//    is GTaskAccess -> Node.Expr.BinaryOp(
//        Node.Expr.Name("tasks"),
//        Node.Expr.BinaryOp.Oper.Token(Node.Expr.BinaryOp.Token.DOT),
//        Node.Expr.Call(
//            Node.Expr.Name("named"),
//            listOf(Node.Type(emptyList(), Node.TypeRef.Simple(listOf(Node.TypeRef.Simple.Piece(type ?: "Task", emptyList()))))),
//            listOf(Node.ValueArg(null, false, Node.Expr.StringTmpl(listOf(Node.Expr.StringTmpl.Elem.Regular(task)), false))),
//            if (this is GTaskConfigure) lambda(configure.toKotlin().cast()) else null
//        )
//    )
//    is GBuildScriptBlock -> call(
//        expr = name(type.text),
//        lambda = lambda(block.toKotlin().cast())
//    )
//    is GVariableDeclaration -> {
//        property(
//            vars = listOf(
//                Node.Decl.Property.Var(
//                    name.name,
//                    type?.let {
//                        Node.Type(
//                            emptyList(),
//                            Node.TypeRef.Nullable(Node.TypeRef.Simple(listOf(Node.TypeRef.Simple.Piece(it, emptyList()))))
//                        )
//                    })
//            ),
//            expr = expr?.toKotlin()?.cast(),
//            readOnly = false
//        )
//    }
//    is GIf -> Node.Expr.If(condition.toKotlin().cast(), body.toKotlin().cast(), elseBody?.toKotlin()?.cast())
//    is GTryCatch -> Node.Expr.Try(
//        body.toKotlin().cast(),
//        catches.map {
//            Node.Expr.Try.Catch(
//                emptyList(),
//                it.name,
//                Node.TypeRef.Simple(listOf(Node.TypeRef.Simple.Piece(it.type, emptyList()))),
//                it.block.toKotlin().cast()
//            )
//        },
//        finallyBody?.toKotlin()?.cast()
//    )
//    is GSwitchCase -> Node.Expr.When.Entry(
//        listOf(Node.Expr.When.Cond.Expr(expr.toKotlin().cast())),
//        body.toKotlin().cast()
//    )
//    is GSwitch -> {
//        val entries = cases.map { it.toKotlin() as Node.Expr.When.Entry }
//        Node.Expr.When(
//            expr.toKotlin().cast(),
//            if (default == null) entries else entries.plus(default!!.toKotlin() as Node.Expr.When.Entry)
//        )
//    }
//}