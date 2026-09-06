package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import dev.greeny.kmr.language.psi.KmrPascalDocComment;
import dev.greeny.kmr.language.psi.KmrPascalReferenceElement;
import org.jetbrains.annotations.NotNull;

/** Uses of declarations carrying a {@code @deprecated} doc tag (API members marked in the stubs, or user code). */
public class KmrPascalDeprecatedInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (!(element instanceof KmrPascalReferenceElement) || KmrPascalInspectionUtil.shouldSkip(element)) {
					return;
				}
				PsiElement target = KmrPascalInspectionUtil.resolveSingle(element);
				KmrPascalDocComment doc = target == null ? null : KmrPascalDocComment.of(target);
				if (doc == null || !doc.hasTag(KmrPascalDocComment.TAG_DEPRECATED)) {
					return;
				}
				String message = doc.getTagValue(KmrPascalDocComment.TAG_DEPRECATED);
				String name = ((KmrPascalReferenceElement) element).getReferenceName();
				KmrPascalInspectionUtil.report(holder, element, ((KmrPascalReferenceElement) element).getNameIdentifier().getTextRangeInParent(),
					"'" + name + "' is deprecated" + (message == null || message.isEmpty() ? "" : ": " + message),
					ProblemHighlightType.LIKE_DEPRECATED);
			}
		};
	}

}
