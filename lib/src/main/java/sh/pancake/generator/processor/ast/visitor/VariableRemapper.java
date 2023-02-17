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
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Name;

import sh.pancake.generator.processor.ast.NameAlloc;

public class VariableRemapper extends TreeTranslator {
    private final NameAlloc nameAlloc;

    private final LinkedList<Map<Name, Name>> scopeVariableMap;

    public VariableRemapper(NameAlloc nameAlloc) {
        this.nameAlloc = nameAlloc;
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

    private Map<Name, Name> push() {
        Map<Name, Name> map = new HashMap<>();
        scopeVariableMap.add(map);
        return map;
    }

    @Nullable
    private Map<Name, Name> pop() {
        return scopeVariableMap.pollLast();
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
        push();
        super.visitBlock(tree);
        pop();
    }

    @Override
    public void visitForLoop(JCForLoop tree) {
        push();
        super.visitForLoop(tree);
        pop();
    }

    @Override
    public void visitForeachLoop(JCEnhancedForLoop tree) {
        push();
        super.visitForeachLoop(tree);
        pop();
    }

    @Override
    public void visitCatch(JCCatch tree) {
        push();
        super.visitCatch(tree);
        pop();
    }

    @Override
    public void visitVarDef(JCVariableDecl tree) {
        Name mappedName = nameAlloc.nextName(tree.name.toString());
        Map<Name, Name> current = scopeVariableMap.peekLast();
        if (current == null) {
            current = push();
        }

        current.put(tree.name, mappedName);
        tree.name = mappedName;
        super.visitVarDef(tree);
    }
}
