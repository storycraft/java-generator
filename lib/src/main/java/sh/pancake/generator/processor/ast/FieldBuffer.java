/*
 * Created on Fri Feb 10 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast;

import javax.annotation.Nullable;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;

public class FieldBuffer {
    private static final String VAR_PREFIX = "@";

    private final TreeMaker treeMaker;
    private final Names names;

    private final JCModifiers privateModifiers;

    private int nextFieldId;

    public final ListBuffer<JCVariableDecl> fields;

    public FieldBuffer(Context cx) {
        treeMaker = TreeMaker.instance(cx);
        names = Names.instance(cx);

        privateModifiers = treeMaker.Modifiers(Flags.PRIVATE);

        nextFieldId = 0;

        fields = new ListBuffer<>();
    }

    public JCVariableDecl nextPrivateField(JCExpression type) {
        return nextPrivateField(type, null);
    }

    public JCVariableDecl nextPrivateField(JCExpression type, @Nullable JCExpression init) {
        return nextField(privateModifiers, type, init);
    }

    public JCVariableDecl nextField(JCModifiers mods, JCExpression type, @Nullable JCExpression init) {
        JCVariableDecl decl = treeMaker.VarDef(mods, names.fromString(VAR_PREFIX + (nextFieldId++)), type, init);
        fields.add(decl);
        return decl;
    }

    public List<JCVariableDecl> build() {
        return fields.toList();
    }
}
