package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.stubs.KmrPascalEvent;
import dev.greeny.kmr.language.stubs.KmrPascalEvents;
import dev.greeny.kmr.language.types.KmrTypePresenter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * A routine named like a game event (or registered with {$EVENT}) must have the event's signature: a procedure with
 * the same parameter count and types. Offers to rewrite the header.
 */
public class KmrPascalEventHandlerInspection extends LocalInspectionTool
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
		String name = routine.getName();
		PsiElement identifier = routine.getNameIdentifier();
		if (name == null || identifier == null || KmrPascalInspectionUtil.shouldSkip(routine)) {
			return;
		}
		PsiFile file = routine.getContainingFile();
		String version = KmrPascalInspectionUtil.versionOf(file);
		KmrPascalEvents events = KmrPascalEvents.getInstance(file.getProject());
		KmrPascalEvent event = events.findByHandlerName(version, name);
		if (event == null) {
			String eventName = KmrPascalInspectionUtil.registeredEventName(KmrPascalInspectionUtil.unitOf(file), name);
			event = eventName == null ? null : events.findByEventName(version, eventName);
		}
		if (event == null) {
			return;
		}
		KmrPascalCallable expected = event.getSignature();
		KmrPascalCallable actual = KmrPascalCallable.of(routine);
		if (actual != null && matches(expected, actual)) {
			return;
		}
		String header = "procedure " + name + expected.getSignatureText(false);
		holder.registerProblem(identifier, "Event handler '" + name + "' must be declared as '" + header + "'",
			ProblemHighlightType.GENERIC_ERROR, new ChangeHeaderFix(header));
	}

	/** Same kind (procedure), same parameter count, same parameter types (names may differ). */
	private static boolean matches(@NotNull KmrPascalCallable expected, @NotNull KmrPascalCallable actual)
	{
		if (actual.isFunction() != expected.isFunction()) {
			return false;
		}
		List<KmrPascalParameterIdentifier> expectedParameters = expected.getParameterIdentifiers();
		List<KmrPascalParameterIdentifier> actualParameters = actual.getParameterIdentifiers();
		if (expectedParameters.size() != actualParameters.size()) {
			return false;
		}
		for (int i = 0; i < expectedParameters.size(); i++) {
			if (!typeOf(expectedParameters.get(i)).equals(typeOf(actualParameters.get(i)))) {
				return false;
			}
		}
		return true;
	}

	/** Plain type text: the stubs' kind aliases ({@code TKMHouseID}) compare as their underlying type. */
	@NotNull
	private static String typeOf(@NotNull KmrPascalParameterIdentifier parameter)
	{
		return KmrTypePresenter.plainTypeText(parameter.getType()).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
	}

	/** Replaces everything from the routine keyword up to the header's ";" with the expected header. */
	static final class ChangeHeaderFix implements LocalQuickFix
	{
		private final String header;

		ChangeHeaderFix(@NotNull String header)
		{
			this.header = header;
		}

		@Override
		public @NotNull String getFamilyName()
		{
			return "Change to '" + header + "'";
		}

		@Override
		public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor)
		{
			KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), KmrPascalRoutineDeclaration.class, false);
			PsiElement semicolon = routine == null ? null : headerSemicolon(routine);
			Document document = routine == null ? null : PsiDocumentManager.getInstance(project).getDocument(routine.getContainingFile());
			if (semicolon == null || document == null) {
				return;
			}
			document.replaceString(routine.getTextRange().getStartOffset(), semicolon.getTextRange().getStartOffset(), header);
			PsiDocumentManager.getInstance(project).commitDocument(document);
		}

		/**
		 * The ";" ending the header. Parameter groups are separated by ";" tokens that are direct children too (the
		 * argument list rule is private), so take the first ";" after the name, the closing ")" and the return type.
		 */
		@Nullable
		private static PsiElement headerSemicolon(@NotNull KmrPascalRoutineDeclaration routine)
		{
			int headerEnd = routine.getNameIdentifier() == null ? routine.getTextRange().getStartOffset() : routine.getNameIdentifier().getTextRange().getEndOffset();
			for (PsiElement child = routine.getFirstChild(); child != null; child = child.getNextSibling()) {
				if (child.getNode().getElementType() == KmrPascalTypes.RBRACKET || child instanceof KmrPascalTypeSpec) {
					headerEnd = Math.max(headerEnd, child.getTextRange().getEndOffset());
				}
			}
			for (PsiElement child = routine.getFirstChild(); child != null; child = child.getNextSibling()) {
				if (child.getNode().getElementType() == KmrPascalTypes.SEMI && child.getTextRange().getStartOffset() >= headerEnd) {
					return child;
				}
			}
			return null;
		}
	}

}
