package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

/** Local variables and parameters of a routine that are never referenced. Event handler parameters are exempt. */
public class KmrPascalUnusedLocalInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (element instanceof KmrPascalRoutineDeclaration) {
					check((KmrPascalRoutineDeclaration) element, holder);
				}
			}
		};
	}

	private static void check(@NotNull KmrPascalRoutineDeclaration routine, @NotNull ProblemsHolder holder)
	{
		if (KmrPascalInspectionUtil.shouldSkip(routine) || routine.getStatementList() == null) {
			return;
		}
		Set<PsiElement> used = new HashSet<>();
		for (KmrPascalReferenceElement reference : PsiTreeUtil.findChildrenOfType(routine, KmrPascalReferenceElement.class)) {
			PsiReference psiReference = reference.getReference();
			if (psiReference instanceof PsiPolyVariantReference) {
				for (ResolveResult result : ((PsiPolyVariantReference) psiReference).multiResolve(false)) {
					used.add(result.getElement());
				}
			} else if (psiReference != null) {
				used.add(psiReference.resolve());
			}
		}
		boolean eventHandler = KmrPascalInspectionUtil.isEventHandler(routine);
		if (!eventHandler) {
			for (KmrPascalParameterDeclaration parameter : routine.getParameters()) {
				for (KmrPascalParameterIdentifier identifier : parameter.getParameterIdentifierList()) {
					if (!used.contains(identifier) && identifier.getNameIdentifier() != null) {
						holder.registerProblem(identifier.getNameIdentifier(), "Unused parameter '" + identifier.getName() + "'", ProblemHighlightType.LIKE_UNUSED_SYMBOL);
					}
				}
			}
		}
		for (PsiElement child : routine.getChildren()) {
			for (KmrPascalNamedElement local : KmrPascalCompilationUnit.topLevelDeclarations(child)) {
				if (local instanceof KmrPascalVarIdentifier && !used.contains(local) && local.getNameIdentifier() != null) {
					holder.registerProblem(local.getNameIdentifier(), "Unused local variable '" + local.getName() + "'", ProblemHighlightType.LIKE_UNUSED_SYMBOL);
				}
			}
		}
	}

}
