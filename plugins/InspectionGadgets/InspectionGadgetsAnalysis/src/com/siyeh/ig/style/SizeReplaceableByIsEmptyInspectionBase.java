/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package com.siyeh.ig.style;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.containers.OrderedSet;
import com.siyeh.HardcodedMethodConstants;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.PsiReplacementUtil;
import com.siyeh.ig.psiutils.CommentTracker;
import com.siyeh.ig.psiutils.ComparisonUtils;
import com.siyeh.ig.psiutils.ExpressionUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class SizeReplaceableByIsEmptyInspectionBase extends BaseInspection {
  @SuppressWarnings({"PublicField"})
  public boolean ignoreNegations = false;
  @SuppressWarnings("PublicField")
  public OrderedSet<String> ignoredTypes = new OrderedSet<>();

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message("size.replaceable.by.isempty.display.name");
  }

  @Override
  @NotNull
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message("expression.can.be.replaced.problem.descriptor", infos[0]);
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new SizeReplaceableByIsEmptyVisitor();
  }

  protected static class SizeReplaceableByIsEmptyFix
    extends InspectionGadgetsFix {

    @Override
    @NotNull
    public String getFamilyName() {
      return InspectionGadgetsBundle.message("size.replaceable.by.isempty.quickfix");
    }

    @Override
    protected void doFix(Project project, ProblemDescriptor descriptor) {
      final PsiBinaryExpression binaryExpression = (PsiBinaryExpression)descriptor.getPsiElement();
      PsiExpression operand = binaryExpression.getLOperand();
      if (!(operand instanceof PsiMethodCallExpression)) {
        operand = binaryExpression.getROperand();
      }
      if (!(operand instanceof PsiMethodCallExpression)) {
        return;
      }
      final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)operand;
      final PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
      final PsiExpression qualifierExpression = methodExpression.getQualifierExpression();
      if (qualifierExpression == null) {
        return;
      }
      CommentTracker commentTracker = new CommentTracker();
      @NonNls String newExpression = commentTracker.text(qualifierExpression);
      final IElementType tokenType = binaryExpression.getOperationTokenType();
      if (!JavaTokenType.EQEQ.equals(tokenType)) {
        newExpression = '!' + newExpression;
      }
      newExpression += ".isEmpty()";

      PsiReplacementUtil.replaceExpression(binaryExpression, newExpression, commentTracker);
    }
  }

  private class SizeReplaceableByIsEmptyVisitor extends BaseInspectionVisitor {

    @Override
    public void visitBinaryExpression(PsiBinaryExpression expression) {
      super.visitBinaryExpression(expression);
      final PsiExpression rhs = expression.getROperand();
      if (rhs == null) {
        return;
      }
      if (!ComparisonUtils.isComparison(expression)) {
        return;
      }
      final PsiExpression lhs = expression.getLOperand();
      if (lhs instanceof PsiMethodCallExpression) {
        final String replacementIsEmptyCall = getReplacementIsEmptyCall(lhs, rhs, false, expression.getOperationTokenType());
        if (replacementIsEmptyCall != null) {
          registerError(expression, replacementIsEmptyCall);
        }
      }
      else if (rhs instanceof PsiMethodCallExpression) {
        final String replacementIsEmptyCall = getReplacementIsEmptyCall(rhs, lhs, true, expression.getOperationTokenType());
        if (replacementIsEmptyCall != null) {
          registerError(expression, replacementIsEmptyCall);
        }
      }
    }

    @Nullable
    private String getReplacementIsEmptyCall(PsiExpression lhs, PsiExpression rhs, boolean flipped, IElementType tokenType) {
      final PsiMethodCallExpression callExpression = (PsiMethodCallExpression)lhs;
      final String isEmptyCall = getIsEmptyCall(callExpression);
      if (isEmptyCall == null) {
        return null;
      }
      final Object object = ExpressionUtils.computeConstantExpression(rhs);
      if (!(object instanceof Integer)) {
        return null;
      }
      final Integer integer = (Integer)object;
      final int constant = integer.intValue();
      if (constant != 0) {
        return null;
      }
      if (JavaTokenType.EQEQ.equals(tokenType)) {
        return isEmptyCall;
      }
      if (ignoreNegations) {
        return null;
      }
      if (JavaTokenType.NE.equals(tokenType)) {
        return '!' + isEmptyCall;
      }
      else if (flipped) {
        if (JavaTokenType.LT.equals(tokenType)) {
          return '!' + isEmptyCall;
        }
      }
      else if (JavaTokenType.GT.equals(tokenType)) {
        return '!' + isEmptyCall;
      }
      return null;
    }

    @Nullable
    private String getIsEmptyCall(PsiMethodCallExpression callExpression) {
      final PsiReferenceExpression methodExpression = callExpression.getMethodExpression();
      final String referenceName = methodExpression.getReferenceName();
      if (!HardcodedMethodConstants.SIZE.equals(referenceName) &&
        !HardcodedMethodConstants.LENGTH.equals(referenceName)) {
        return null;
      }
      final PsiExpressionList argumentList = callExpression.getArgumentList();
      final PsiExpression[] expressions = argumentList.getExpressions();
      if (expressions.length != 0) {
        return null;
      }
      final PsiExpression qualifierExpression = methodExpression.getQualifierExpression();
      if (qualifierExpression == null) {
        return null;
      }
      final PsiClass aClass = PsiUtil.resolveClassInClassTypeOnly(qualifierExpression.getType());
      if (aClass == null || PsiTreeUtil.isAncestor(aClass, callExpression, true)) {
        return null;
      }
      for (String ignoredType : ignoredTypes) {
        if (InheritanceUtil.isInheritor(aClass, ignoredType)) {
          return null;
        }
      }
      final PsiMethod[] methods = aClass.findMethodsByName("isEmpty", true);
      for (PsiMethod method : methods) {
        final PsiParameterList parameterList = method.getParameterList();
        if (parameterList.isEmpty()) {
          return qualifierExpression.getText() + ".isEmpty()";
        }
      }
      return null;
    }
  }

  @Override
  @Nullable
  protected InspectionGadgetsFix buildFix(Object... infos) {
    return new SizeReplaceableByIsEmptyFix();
  }
}
