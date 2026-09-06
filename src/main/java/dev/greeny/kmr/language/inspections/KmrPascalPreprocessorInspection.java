package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.SyntaxTraverser;
import dev.greeny.kmr.language.editor.KmrPascalEventDirectiveReference;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import dev.greeny.kmr.language.stubs.KmrPascalEvents;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Problems the preprocessor found while building the compilation unit the file is analysed in: missing or recursive
 * includes, a file included twice with active content, unbalanced {$IFDEF}/{$ENDIF}, {$ELSE}/{$ENDIF} without {$IF}.
 * Also {$EVENT} directives that name an unknown event or a procedure the unit does not declare.
 */
public class KmrPascalPreprocessorInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (element instanceof PsiFile && !KmrPascalStubLibrary.isStubFile((PsiFile) element)) {
					check((PsiFile) element, holder);
				}
			}
		};
	}

	private static void check(@NotNull PsiFile file, @NotNull ProblemsHolder holder)
	{
		KmrPascalCompilationUnit unit = KmrPascalInspectionUtil.unitOf(file);
		checkEventDirectives(file, unit, holder);
		for (KmrPascalCompilationUnit.Problem problem : unit.getProblems()) {
			if (!problem.file.isEquivalentTo(file.getOriginalFile()) || problem.range.isEmpty()) {
				continue;
			}
			PsiElement element = file.findElementAt(problem.range.getStartOffset());
			if (element == null) {
				continue;
			}
			ProblemHighlightType type = problem.kind == KmrPascalCompilationUnit.Problem.Kind.DUPLICATE_ACTIVE_INCLUDE
				? ProblemHighlightType.WARNING : ProblemHighlightType.GENERIC_ERROR;
			holder.registerProblem(element, problem.message, type);
		}
	}

	private static void checkEventDirectives(@NotNull PsiFile file, @NotNull KmrPascalCompilationUnit unit, @NotNull ProblemsHolder holder)
	{
		for (PsiComment comment : SyntaxTraverser.psiTraverser(file).filter(PsiComment.class)) {
			if (comment.getNode().getElementType() != KmrPascalTypes.DIRECTIVE_EVENT || !unit.isActive(comment)) {
				continue;
			}
			for (PsiReference reference : comment.getReferences()) {
				if (!(reference instanceof KmrPascalEventDirectiveReference) || reference.resolve() != null) {
					continue;
				}
				KmrPascalEventDirectiveReference directiveReference = (KmrPascalEventDirectiveReference) reference;
				String name = directiveReference.getName();
				if (name.isEmpty()) {
					continue;
				}
				String message = directiveReference.isHandlerPart()
					? "Procedure '" + name + "' is not declared in the compilation unit"
					: "Unknown event '" + name + "'" + otherVersionsWithEvent(file, name);
				KmrPascalInspectionUtil.report(holder, comment, reference.getRangeInElement(), message, ProblemHighlightType.GENERIC_ERROR);
			}
		}
	}

	@NotNull
	private static String otherVersionsWithEvent(@NotNull PsiFile file, @NotNull String eventName)
	{
		String current = KmrPascalInspectionUtil.versionOf(file);
		KmrPascalEvents events = KmrPascalEvents.getInstance(file.getProject());
		List<String> versions = new ArrayList<>();
		for (String version : KmrPascalStubLibrary.VERSIONS) {
			if (!version.equals(current) && events.findByEventName(version, eventName) != null) {
				versions.add(version);
			}
		}
		return versions.isEmpty() ? "" : " (available in game version " + String.join(", ", versions) + ")";
	}

}
