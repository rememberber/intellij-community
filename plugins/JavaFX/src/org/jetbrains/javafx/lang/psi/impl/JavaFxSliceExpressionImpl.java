package org.jetbrains.javafx.lang.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.javafx.lang.parser.JavaFxElementTypes;
import org.jetbrains.javafx.lang.psi.JavaFxExpression;
import org.jetbrains.javafx.lang.psi.JavaFxSliceExpression;
import org.jetbrains.javafx.lang.psi.impl.types.JavaFxSequenceTypeImpl;

/**
 * Created by IntelliJ IDEA.
 * Author: Alexey.Ivanov
 * Date:   12.05.2010
 * Time:   17:42:18
 */
public class JavaFxSliceExpressionImpl extends JavaFxBaseElementImpl implements JavaFxSliceExpression {
  public JavaFxSliceExpressionImpl(@NotNull ASTNode node) {
    super(node);
  }

  @NotNull
  @Override
  public JavaFxExpression getOperand() {
    //noinspection ConstantConditions
    return (JavaFxExpression)childToPsi(JavaFxElementTypes.EXPRESSIONS, 0);
  }

  @Override
  public PsiType getType() {
    final PsiType type = getOperand().getType();
    if (type instanceof JavaFxSequenceTypeImpl) {
      return ((JavaFxSequenceTypeImpl)type).getElementType();
    }
    return null;
  }
}
