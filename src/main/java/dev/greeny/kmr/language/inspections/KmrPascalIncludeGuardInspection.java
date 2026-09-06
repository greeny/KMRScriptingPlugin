package dev.greeny.kmr.language.inspections;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.util.IncorrectOperationException;
import dev.greeny.kmr.language.psi.KmrPascalFile;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.unit.KmrPascalDirective;
import dev.greeny.kmr.language.unit.KmrPascalEntryPoints;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A script included from more than one place should be wrapped in the include guard idiom
 * {@code {$IFNDEF x} {$DEFINE x} ... {$ENDIF}}, otherwise its declarations are duplicated when both includers end up
 * in the same compilation unit. The fix (also available as an intention on any un-guarded script) adds the guard.
 */
public class KmrPascalIncludeGuardInspection extends LocalInspectionTool
{

	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly)
	{
		return new PsiElementVisitor()
		{
			@Override
			public void visitElement(@NotNull PsiElement element)
			{
				if (element instanceof KmrPascalFile) {
					check((KmrPascalFile) element, holder);
				}
			}
		};
	}

	private static void check(@NotNull KmrPascalFile file, @NotNull ProblemsHolder holder)
	{
		VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
		if (virtualFile == null || KmrPascalStubLibrary.isStubFile(file) || KmrPascalEntryPoints.isEntryPoint(file.getProject(), virtualFile)) {
			return;
		}
		if (isGuarded(file) || !hasCode(file)) {
			return;
		}
		int includers = KmrPascalEntryPoints.includersOf(file.getProject(), virtualFile).size();
		if (includers < 2) {
			return;
		}
		PsiElement first = firstCodeElement(file);
		if (first == null) {
			return;
		}
		holder.registerProblem(first, "This script is included from " + includers + " places but has no include guard",
			ProblemHighlightType.WEAK_WARNING, new AddIncludeGuardFix());
	}

	/** {$IFNDEF x} first, {$DEFINE x} inside, {$ENDIF} last, nothing but whitespace/comments outside. */
	static boolean isGuarded(@NotNull PsiFile file)
	{
		List<KmrPascalDirective> directives = KmrPascalDirective.collect(file);
		if (directives.size() < 3) {
			return false;
		}
		KmrPascalDirective first = directives.get(0);
		KmrPascalDirective last = directives.get(directives.size() - 1);
		if (first.kind != KmrPascalDirective.Kind.IFNDEF || last.kind != KmrPascalDirective.Kind.ENDIF || first.argument.isEmpty()) {
			return false;
		}
		boolean defines = directives.stream().anyMatch(d -> d.kind == KmrPascalDirective.Kind.DEFINE && d.argument.equalsIgnoreCase(first.argument));
		if (!defines) {
			return false;
		}
		// the guard must enclose everything
		return onlyBlankOrComments(file, 0, first.range.getStartOffset()) && onlyBlankOrComments(file, last.range.getEndOffset(), file.getTextLength());
	}

	private static boolean onlyBlankOrComments(@NotNull PsiFile file, int from, int to)
	{
		for (int offset = from; offset < to; ) {
			PsiElement element = file.findElementAt(offset);
			if (element == null) {
				return true;
			}
			if (!(element instanceof PsiWhiteSpace) && !(element instanceof PsiComment)) {
				return false;
			}
			offset = element.getTextRange().getEndOffset();
		}
		return true;
	}

	static boolean hasCode(@NotNull PsiFile file)
	{
		return firstCodeElement(file) != null;
	}

	/** First top-level child that is not whitespace or a comment/directive. */
	@Nullable
	static PsiElement firstCodeElement(@NotNull PsiFile file)
	{
		for (PsiElement child = file.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (!(child instanceof PsiWhiteSpace) && !(child instanceof PsiComment)) {
				return child;
			}
		}
		return null;
	}

	/** Guard symbol derived from the file name: consts.script -> constsScript. */
	@NotNull
	static String guardSymbol(@NotNull PsiFile file)
	{
		String base = file.getName();
		int dot = base.lastIndexOf('.');
		if (dot > 0) {
			base = base.substring(0, dot);
		}
		StringBuilder symbol = new StringBuilder();
		for (char c : base.toCharArray()) {
			symbol.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
		}
		if (symbol.length() == 0 || Character.isDigit(symbol.charAt(0))) {
			symbol.insert(0, '_');
		}
		return symbol + "Script";
	}

	/** Wraps the whole file in the guard idiom. Usable as a quick fix and as an intention. */
	public static final class AddIncludeGuardFix implements LocalQuickFix, IntentionAction
	{
		@Override
		public @NotNull String getFamilyName()
		{
			return "Add include guard";
		}

		@Override
		public @NotNull String getText()
		{
			return getFamilyName();
		}

		@Override
		public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file)
		{
			VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
			return file instanceof KmrPascalFile && virtualFile != null && !KmrPascalStubLibrary.isStubFile(file)
				&& !KmrPascalEntryPoints.isEntryPoint(project, virtualFile) && !isGuarded(file) && hasCode(file);
		}

		@Override
		public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException
		{
			addGuard(project, file);
		}

		@Override
		public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor)
		{
			addGuard(project, descriptor.getPsiElement().getContainingFile());
		}

		@Override
		public boolean startInWriteAction()
		{
			return true;
		}

		private static void addGuard(@NotNull Project project, @NotNull PsiFile file)
		{
			Document document = PsiDocumentManager.getInstance(project).getDocument(file);
			if (document == null) {
				return;
			}
			String symbol = guardSymbol(file);
			String text = document.getText();
			String tail = text.endsWith("\n") ? "" : "\n";
			document.insertString(document.getTextLength(), tail + "{$ENDIF}\n");
			document.insertString(0, "{$IFNDEF " + symbol + "}\n{$DEFINE " + symbol + "}\n\n");
			PsiDocumentManager.getInstance(project).commitDocument(document);
		}
	}

}
