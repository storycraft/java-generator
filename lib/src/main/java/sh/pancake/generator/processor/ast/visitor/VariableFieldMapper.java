/*
 * Created on Sun Feb 12 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast.visitor;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import javax.annotation.Nullable;

import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;

import sh.pancake.generator.processor.ast.FieldBuffer;

public class VariableFieldMapper extends TreeTranslator {
    private final TreeMaker treeMaker;

    private final FieldBuffer fieldBuffer;

    private final LinkedList<Map<Name, Name>> scopeVariableMap;

    public VariableFieldMapper(Context cx, FieldBuffer fieldBuffer) {
        this.treeMaker = TreeMaker.instance(cx);
        this.fieldBuffer = fieldBuffer;
        this.scopeVariableMap = new LinkedList<>();
    }

    @Nullable
    private Name getConverted(Name name) {
        for (Map<Name, Name> scope : (Iterable<Map<Name, Name>>) () -> scopeVariableMap.descendingIterator()) {
            Name converted = scope.get(name);
            if (converted != null) {
                return converted;
            }
        }

        return null;
    }

    private <T extends JCTree> void addScope(T tree) {
        Map<Name, Name> scopeMap = new HashMap<>();
        tree.accept(new ScopeVariableCollector(fieldBuffer, scopeMap));
        scopeVariableMap.add(scopeMap);
    }

    private <T extends JCTree> void addScopeList(List<T> trees) {
        Map<Name, Name> scopeMap = new HashMap<>();

        ScopeVariableCollector collector = new ScopeVariableCollector(fieldBuffer, scopeMap);
        for (JCTree tree : trees) {
            tree.accept(collector);
        }

        scopeVariableMap.add(scopeMap);
    }

    @Override
    public void visitIdent(JCIdent tree) {
        super.visitIdent(tree);

        Name converted = getConverted(tree.name);
        if (converted != null) {
            tree.name = converted;
        }
    }

    @Override
    public void visitBlock(JCBlock tree) {
        addScopeList(tree.stats);
        super.visitBlock(tree);
        scopeVariableMap.removeLast();
    }

    @Override
    public void visitForLoop(JCForLoop tree) {
        addScopeList(tree.init);
        super.visitForLoop(tree);
        scopeVariableMap.removeLast();
    }

    @Override
    public void visitForeachLoop(JCEnhancedForLoop tree) {
        addScope(tree.var);
        super.visitForeachLoop(tree);
        scopeVariableMap.removeLast();
    }

    @Override
    public void visitCatch(JCCatch tree) {
        addScope(tree.param);
        super.visitCatch(tree);
        scopeVariableMap.removeLast();
    }

    @Override
    public void visitVarDef(JCVariableDecl tree) {
        super.visitVarDef(tree);

        Name converted = getConverted(tree.name);
        if (converted != null && tree.init == null) {
            super.result = treeMaker.at(tree.pos).Exec(treeMaker.Assign(treeMaker.Ident(converted), tree.init));
        } else {
            super.result = treeMaker.at(tree.pos).Skip();
        }
    }

    private static class ScopeVariableCollector extends Visitor {
        private final FieldBuffer fieldBuffer;

        private final Map<Name, Name> variableMap;

        public ScopeVariableCollector(FieldBuffer fieldBuffer, Map<Name, Name> variableMap) {
            this.fieldBuffer = fieldBuffer;
            this.variableMap = variableMap;
        }

        @Override
        public void visitVarDef(JCVariableDecl tree) {
            variableMap.put(tree.name, fieldBuffer.nextPrivateField(tree.vartype).name);
        }

        @Override
        public void visitTree(JCTree that) {
        }
    }
}
