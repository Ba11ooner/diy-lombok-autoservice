package com.diy.lombok.processor;

import com.diy.lombok.annotation.MySetter;
import com.google.auto.service.AutoService;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.lang.reflect.Method;
import java.util.Set;

@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes("com.diy.lombok.annotation.MySetter")
@AutoService(Processor.class)
public class MySetterProcessor extends AbstractProcessor {

    private Messager messager; // 用于输出编译器日志
    private JavacTrees javacTrees; // 提供了待操作的抽象语法树
    private TreeMaker treeMaker; // 封装了操作 AST 的方法
    private Names names; // 提供了创建标识符的方法

    //region 初始化逻辑：正常初始化 + 获取工具类实例
    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        // 调用父类方法，正常进行初始化
        super.init(processingEnv);

        processingEnv = jbUnwrap(ProcessingEnvironment.class, processingEnv);

        // 从环境中获取工具类
        this.messager = processingEnv.getMessager();
        this.javacTrees = JavacTrees.instance(processingEnv);
        Context context = ((JavacProcessingEnvironment) processingEnv).getContext();
        this.treeMaker = TreeMaker.instance(context);
        this.names = Names.instance(context);
        messager.printMessage(Diagnostic.Kind.NOTE, "MySetterProcessor init");
    }
    //endregion

    //region 注解处理逻辑：收集变量，为变量生成 setter 方法
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        messager.printMessage(Diagnostic.Kind.NOTE, "MySetterProcessor process");
        // 获取带有 @MySetter 注解的类的所有元素
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(MySetter.class);

        // 遍历所有元素
        elements.forEach(e -> {
            //获取元素的 JCTree
            JCTree tree = javacTrees.getTree(e);

            //为 JCTree 创建一个转换器，实现转换器的访问者方法
            tree.accept(new TreeTranslator() {
                @Override
                public void visitClassDef(JCTree.JCClassDecl tree) {
                    // 创建一个空列表
                    List<JCTree.JCVariableDecl> jcVariableDeclList = List.nil();

                    // 收集所有变量
                    for (JCTree jcTree : tree.defs) {
                        if (jcTree.getKind().equals(Tree.Kind.VARIABLE)) {
                            JCTree.JCVariableDecl jcVariableDecl = (JCTree.JCVariableDecl) jcTree;
                            jcVariableDeclList = jcVariableDeclList.append(jcVariableDecl);
                        }
                    }

                    // 为所有变量进行方法生成的操作
                    jcVariableDeclList.forEach(jcVariableDecl -> {
                        messager.printMessage(Diagnostic.Kind.NOTE,
                                getNewMethodName(jcVariableDecl.getName())
                                        + " is created");
                        tree.defs = tree.defs.prepend(makeSetterMethod(jcVariableDecl));
                    });

                    // 执行父类的访问者方法
                    super.visitClassDef(tree);
                }
            });
        });
        return true;
    }
    //endregion

    //region 私有方法：用于生成 setter 方法，针对每一个变量
    // setter 方法示例
    // void setName(String name){
    //     this.name = name;
    // }
    private JCTree.JCMethodDecl makeSetterMethod(JCTree.JCVariableDecl jcVariableDecl) {
        // 语句列表
        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<>();

        // 生成表达式 this.name = name;
        JCTree.JCExpressionStatement aThis = makeAssignment(
                // 左表达式：this.name
                treeMaker.Select(
                        treeMaker.Ident(names.fromString("this")),
                        jcVariableDecl.getName() //获取变量名称
                ),
                // 右表达式：name
                treeMaker.Ident(jcVariableDecl.getName()));

        // 收集语句
        statements.append(aThis);

        // 生成代码块
        JCTree.JCBlock block = treeMaker.Block(0, statements.toList());

        // 生成参数
        JCTree.JCVariableDecl param = treeMaker.VarDef(
                treeMaker.Modifiers(Flags.PARAMETER),
                jcVariableDecl.getName(),
                jcVariableDecl.vartype,
                null
        );
        List<JCTree.JCVariableDecl> parameters = List.of(param);

        // 生成返回值类型
        JCTree.JCExpression methodType = treeMaker.Type(new Type.JCVoidType());

        // 生成返回对象：setter 方法
        return treeMaker.MethodDef(
                treeMaker.Modifiers(Flags.PUBLIC), // 访问权限标识符
                getNewMethodName(jcVariableDecl.getName()), // 方法名称
                methodType, // 返回值类型
                List.nil(), // 方法的异常列表，这里为空
                parameters, // 方法参数列表
                List.nil(), // 方法的类型参数列表（方法或类的参数化类型，比如在定义泛型方法或类时，可以指定一些类型参数），这里为空
                block, // 方法体的代码块
                null // 方法参数的默认值（当调用方法时，如果不传入需要的参数，那么这些参数会使用默认值），这里为空
        );
    }
    //endregion

    //region 私有方法：用于生成方法名 setXxx（小驼峰）
    private Name getNewMethodName(Name name) {
        String s = name.toString();
        return names.fromString(
                "set" + s.substring(0, 1).toUpperCase()
                        + s.substring(1, name.length()));
    }
    //endregion

    //region 私有方法：用于生成赋值语句
    private JCTree.JCExpressionStatement makeAssignment(
            JCTree.JCExpression leftExpr, JCTree.JCExpression rightExpr) {
        // 返回赋值语句：leftExpr = rightExpr
        return treeMaker.Exec(treeMaker.Assign(leftExpr, rightExpr));
    }
    //endregion

    //region 解包
    private static <T> T jbUnwrap(Class<? extends T> iface, T wrapper) {
        T unwrapped = null;
        try {
            final Class<?> apiWrappers = wrapper.getClass().getClassLoader().loadClass("org.jetbrains.jps.javac.APIWrappers");
            final Method unwrapMethod = apiWrappers.getDeclaredMethod("unwrap", Class.class, Object.class);
            unwrapped = iface.cast(unwrapMethod.invoke(null, iface, wrapper));
        } catch (Throwable ignored) {
        }
        return unwrapped != null ? unwrapped : wrapper;
    }
    //endregion

}
