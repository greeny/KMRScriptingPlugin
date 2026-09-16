package dev.greeny.kmr.language.refactoring;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pass;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.IntroduceTargetChooser;
import com.intellij.refactoring.RefactoringActionHandler;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import dev.greeny.kmr.language.editor.KmrPascalRenameInputValidator;
import dev.greeny.kmr.language.flow.KmrPurity;
import dev.greeny.kmr.language.flow.KmrStatements;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.resolve.KmrPascalTypeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Extract Method (Ctrl+Alt+M): the selected statements become a procedure (an expression becomes a function) declared
 * before the current routine. Locals the selection reads are passed as parameters, locals it changes and the routine
 * still uses afterwards as {@code var} parameters, locals only the selection uses move into the new routine; globals
 * need nothing. Selections containing {@code exit}, or {@code break}/{@code continue} of an outer loop, cannot be
 * extracted.
 */
public class KmrPascalExtractRoutineHandler implements RefactoringActionHandler
{

	public static final String DEFAULT_NAME = "Extracted";

	@Override
	public void invoke(@NotNull Project project, Editor editor, PsiFile file, DataContext dataContext)
	{
		if (!(file instanceof KmrPascalFile) || editor == null) {
			return;
		}
		SelectionModel selection = editor.getSelectionModel();
		if (selection.hasSelection()) {
			KmrPascalExpression expression = KmrPascalRefactoringUtil.expressionInRange(file, selection.getSelectionStart(), selection.getSelectionEnd());
			if (expression != null && KmrPascalRefactoringUtil.isExtractable(expression)) {
				extractExpression(project, editor, expression);
				return;
			}
			List<PsiElement> statements = statementsInRange(file, selection.getSelectionStart(), selection.getSelectionEnd());
			if (statements == null) {
				showError(project, editor, "Select an expression or whole statements of one block");
				return;
			}
			extractStatements(project, editor, statements);
			return;
		}
		List<KmrPascalExpression> candidates = KmrPascalRefactoringUtil.extractableExpressionsAt(file, editor.getCaretModel().getOffset());
		if (candidates.isEmpty()) {
			showError(project, editor, "Select the statements or the expression to extract");
			return;
		}
		if (candidates.size() == 1 || ApplicationManager.getApplication().isUnitTestMode()) {
			extractExpression(project, editor, candidates.get(0));
			return;
		}
		IntroduceTargetChooser.showChooser(editor, candidates, new Pass<>()
		{
			@Override
			public void pass(KmrPascalExpression expression)
			{
				extractExpression(project, editor, expression);
			}
		}, PsiElement::getText, "Select Expression");
	}

	@Override
	public void invoke(@NotNull Project project, PsiElement @NotNull [] elements, DataContext dataContext)
	{
		// only invoked from the editor
	}

	// ---------------------------------------------------------------------------------------------------------------
	// selection
	// ---------------------------------------------------------------------------------------------------------------

	/** The whole statements of one block (or the single controlled statement) the selection covers; null otherwise. */
	@Nullable
	static List<PsiElement> statementsInRange(@NotNull PsiFile file, int start, int end)
	{
		TextRange range = KmrPascalRefactoringUtil.trim(file, start, end);
		if (range == null) {
			return null;
		}
		PsiElement first = file.findElementAt(range.getStartOffset());
		PsiElement last = file.findElementAt(range.getEndOffset() - 1);
		if (first == null || last == null) {
			return null;
		}
		PsiElement common = PsiTreeUtil.findCommonParent(first, last);
		// climb out of expressions and one-liners up to a statement whose range fits, or a block
		PsiElement container = common;
		while (container != null && !KmrStatements.isBlock(container) && !(KmrStatements.isStatement(container) && range.contains(container.getTextRange()))) {
			container = container.getParent();
			if (container instanceof KmrPascalRoutineDeclaration || container instanceof PsiFile) {
				return null;
			}
		}
		if (container == null) {
			return null;
		}
		List<PsiElement> statements = new ArrayList<>();
		if (KmrStatements.isBlock(container) && !range.contains(container.getTextRange())) {
			for (PsiElement statement : KmrStatements.statementsOf(container)) {
				if (statement.getTextRange().intersectsStrict(range)) {
					if (!range.contains(statement.getTextRange())) {
						return null; // a statement is cut
					}
					statements.add(statement);
				}
			}
		} else {
			statements.add(container);
		}
		if (statements.isEmpty() || PsiTreeUtil.getParentOfType(statements.get(0), KmrPascalRoutineDeclaration.class) == null) {
			return null;
		}
		// nothing but whitespace, comments and semicolons around the statements
		for (PsiElement leaf = first; leaf != null && leaf.getTextRange().getStartOffset() < range.getEndOffset(); leaf = PsiTreeUtil.nextLeaf(leaf)) {
			boolean inside = false;
			for (PsiElement statement : statements) {
				inside |= PsiTreeUtil.isAncestor(statement, leaf, false);
			}
			if (!inside && !(leaf instanceof PsiWhiteSpace) && !(leaf instanceof PsiComment) && leaf.getNode().getElementType() != KmrPascalTypes.SEMI) {
				return null;
			}
		}
		return statements;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// analysis of the selection
	// ---------------------------------------------------------------------------------------------------------------

	/** One local of the routine the selection touches, and how. */
	static final class Local
	{
		final PsiElement declaration;
		final String name;
		final boolean parameter;
		final boolean result;
		boolean readInside;
		boolean writtenInside;
		/** The first access inside the selection writes it: an input only when it is a parameter or used before. */
		boolean writtenBeforeRead;
		boolean usedBefore;
		boolean usedAfter;
		int firstOffset = Integer.MAX_VALUE;
		final List<KmrPascalReferenceExpression> referencesInside = new ArrayList<>();

		Local(@NotNull PsiElement declaration, @NotNull String name, boolean parameter, boolean result)
		{
			this.declaration = declaration;
			this.name = name;
			this.parameter = parameter;
			this.result = result;
		}

		boolean isInput()
		{
			return parameter || usedBefore || result && readInside && !writtenBeforeRead;
		}

		boolean isOutput()
		{
			return writtenInside && (usedAfter || result || byReferenceParameter());
		}

		/** Neither flows in nor out: the declaration moves into the new routine. */
		boolean isMoved()
		{
			return !parameter && !result && !usedBefore && !usedAfter;
		}

		boolean byReferenceParameter()
		{
			return parameter && declaration.getParent() instanceof KmrPascalParameterDeclaration && KmrPurity.isByReference((KmrPascalParameterDeclaration) declaration.getParent());
		}
	}

	/** The routine-level facts about a selection: which locals flow in, out, or move. */
	static final class Analysis
	{
		final KmrPascalRoutineDeclaration routine;
		final List<Local> locals = new ArrayList<>();
		String error;

		Analysis(@NotNull KmrPascalRoutineDeclaration routine)
		{
			this.routine = routine;
		}
	}

	@NotNull
	static Analysis analyze(@NotNull KmrPascalRoutineDeclaration routine, @NotNull List<PsiElement> selected, @NotNull TextRange range)
	{
		Analysis analysis = new Analysis(routine);
		for (PsiElement statement : selected) {
			if (PsiTreeUtil.findChildOfType(statement, KmrPascalExitStatement.class, false) != null || statement instanceof KmrPascalExitStatement) {
				analysis.error = "Cannot extract: the selection contains 'exit'";
				return analysis;
			}
			for (PsiElement jump : PsiTreeUtil.findChildrenOfAnyType(statement, KmrPascalBreakStatement.class, KmrPascalContinueStatement.class)) {
				PsiElement loop = PsiTreeUtil.getParentOfType(jump, KmrPascalWhileStatement.class, KmrPascalRepeatStatement.class, KmrPascalForStatement.class);
				if (loop == null || !range.contains(loop.getTextRange())) {
					analysis.error = "Cannot extract: the selection contains 'break' or 'continue' of a loop outside it";
					return analysis;
				}
			}
		}
		// the loop around the selection: anything used in it is used both before and after
		PsiElement loop = PsiTreeUtil.getParentOfType(selected.get(0), KmrPascalWhileStatement.class, KmrPascalRepeatStatement.class, KmrPascalForStatement.class);
		TextRange loopRange = loop == null || !PsiTreeUtil.isAncestor(routine, loop, true) ? null : loop.getTextRange();

		Map<PsiElement, Local> locals = new LinkedHashMap<>();
		for (KmrPascalReferenceExpression reference : PsiTreeUtil.findChildrenOfType(routine.getStatementList(), KmrPascalReferenceExpression.class)) {
			PsiElement target = KmrPascalTypeUtil.resolveSingle(reference);
			boolean inside = range.contains(reference.getTextRange());
			if (target instanceof KmrPascalFieldIdentifier && reference instanceof KmrPascalIdentifierExpression && inside) {
				KmrPascalWithStatement with = PsiTreeUtil.getParentOfType(reference, KmrPascalWithStatement.class);
				if (with != null && !range.contains(with.getTextRange())) {
					analysis.error = "Cannot extract: the selection uses fields of a 'with' statement outside it";
					return analysis;
				}
			}
			Local local = localOf(routine, reference, target, locals);
			if (local == null) {
				continue;
			}
			int offset = reference.getTextOffset();
			if (inside) {
				local.referencesInside.add(reference);
				local.firstOffset = Math.min(local.firstOffset, offset);
				boolean written = isWritten(reference);
				if (written) {
					if (!local.readInside) {
						local.writtenBeforeRead = true;
					}
					local.writtenInside = true;
				}
				if (!written || isReadAndWritten(reference)) {
					local.readInside = true;
				}
			} else {
				boolean inLoop = loopRange != null && loopRange.contains(reference.getTextRange());
				if (offset < range.getStartOffset() || inLoop) {
					local.usedBefore = true;
				}
				if (offset >= range.getEndOffset() || inLoop) {
					local.usedAfter = true;
				}
			}
		}
		for (Local local : locals.values()) {
			if (!local.referencesInside.isEmpty()) {
				analysis.locals.add(local);
			}
		}
		analysis.locals.sort(Comparator.comparingInt(local -> local.firstOffset));
		return analysis;
	}

	@Nullable
	private static Local localOf(@NotNull KmrPascalRoutineDeclaration routine, @NotNull KmrPascalReferenceExpression reference, @Nullable PsiElement target, @NotNull Map<PsiElement, Local> locals)
	{
		if (target instanceof KmrPascalParameterIdentifier && PsiTreeUtil.isAncestor(routine, target, true)) {
			return locals.computeIfAbsent(target, t -> new Local(t, String.valueOf(((KmrPascalParameterIdentifier) t).getName()), true, false));
		}
		if (target instanceof KmrPascalVarIdentifier && PsiTreeUtil.isAncestor(routine, target, true)) {
			return locals.computeIfAbsent(target, t -> new Local(t, String.valueOf(((KmrPascalVarIdentifier) t).getName()), false, false));
		}
		if (target == routine && routine instanceof KmrPascalFunctionDeclaration && !isCallee(reference)) {
			return locals.computeIfAbsent(target, t -> new Local(t, "Result", false, true));
		}
		return null;
	}

	private static boolean isCallee(@NotNull KmrPascalExpression expression)
	{
		PsiElement parent = expression.getParent();
		return parent instanceof KmrPascalCallExpression && ((KmrPascalCallExpression) parent).getExpression() == expression;
	}

	/** The reference is (the root of) something assigned to, passed by reference, or the loop variable. */
	private static boolean isWritten(@NotNull KmrPascalReferenceExpression reference)
	{
		PsiElement e = reference;
		while (e.getParent() instanceof KmrPascalIndexExpression && ((KmrPascalIndexExpression) e.getParent()).getExpressionList().get(0) == e
			|| e.getParent() instanceof KmrPascalMemberExpression && ((KmrPascalMemberExpression) e.getParent()).getExpression() == e
			|| e.getParent() instanceof KmrPascalParenExpression) {
			e = e.getParent();
		}
		PsiElement parent = e.getParent();
		if (parent instanceof KmrPascalAssignment && ((KmrPascalAssignment) parent).getExpressionList().get(0) == e) {
			return true;
		}
		if (parent instanceof KmrPascalForStatement && ((KmrPascalForStatement) parent).getExpressionList().get(0) == e) {
			return true;
		}
		if (parent instanceof KmrPascalFunctionArgumentList && parent.getParent() instanceof KmrPascalCallExpression) {
			KmrPascalCallable callable = KmrPascalCallable.of(KmrPascalTypeUtil.resolveSingle(((KmrPascalCallExpression) parent.getParent()).getExpression()));
			int index = ((KmrPascalFunctionArgumentList) parent).getExpressionList().indexOf(e);
			if (callable != null && index >= 0 && index < callable.getParameterCount()) {
				PsiElement group = callable.getParameterIdentifiers().get(index).getParent();
				return group instanceof KmrPascalParameterDeclaration && KmrPurity.isByReference((KmrPascalParameterDeclaration) group);
			}
		}
		return false;
	}

	/** A write that also reads the old value: an element or field assignment, a var argument. */
	private static boolean isReadAndWritten(@NotNull KmrPascalReferenceExpression reference)
	{
		PsiElement parent = reference.getParent();
		return parent instanceof KmrPascalIndexExpression || parent instanceof KmrPascalMemberExpression || parent instanceof KmrPascalFunctionArgumentList;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// the edits
	// ---------------------------------------------------------------------------------------------------------------

	private void extractStatements(@NotNull Project project, @NotNull Editor editor, @NotNull List<PsiElement> statements)
	{
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(statements.get(0), KmrPascalRoutineDeclaration.class);
		if (routine == null) {
			return;
		}
		TextRange range = TextRange.create(statements.get(0).getTextRange().getStartOffset(), statements.get(statements.size() - 1).getTextRange().getEndOffset());
		Analysis analysis = analyze(routine, statements, range);
		if (analysis.error != null) {
			showError(project, editor, analysis.error);
			return;
		}
		String name = askName(project, routine);
		if (name == null) {
			return;
		}
		WriteCommandAction.runWriteCommandAction(project, "Extract Procedure", null, () -> extract(project, routine, analysis, range, null, name), routine.getContainingFile());
	}

	private void extractExpression(@NotNull Project project, @NotNull Editor editor, @NotNull KmrPascalExpression expression)
	{
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(expression, KmrPascalRoutineDeclaration.class);
		if (routine == null) {
			return;
		}
		Analysis analysis = analyze(routine, List.of(expression), expression.getTextRange());
		if (analysis.error != null) {
			showError(project, editor, analysis.error);
			return;
		}
		String name = askName(project, routine);
		if (name == null) {
			return;
		}
		WriteCommandAction.runWriteCommandAction(project, "Extract Function", null,
			() -> extract(project, routine, analysis, expression.getTextRange(), KmrPascalRefactoringUtil.typeText(expression), name), routine.getContainingFile());
	}

	/** Writes the new routine before the current one and replaces the range with the call. */
	static void extract(@NotNull Project project, @NotNull KmrPascalRoutineDeclaration routine, @NotNull Analysis analysis, @NotNull TextRange range,
						@Nullable String resultType, @NotNull String name)
	{
		PsiFile file = routine.getContainingFile();
		Document document = PsiDocumentManager.getInstance(project).getDocument(file);
		if (document == null) {
			return;
		}
		String unit = KmrPascalRefactoringUtil.indentUnit(routine, document);
		boolean function = resultType != null;

		// parameters, arguments, moved declarations
		List<String> parameters = new ArrayList<>();
		List<String> arguments = new ArrayList<>();
		List<String> moved = new ArrayList<>();
		List<Local> movedLocals = new ArrayList<>();
		Map<Local, String> renamed = new HashMap<>();
		for (Local local : analysis.locals) {
			String type = KmrPascalRefactoringUtil.declaredTypeText(local.declaration);
			if (type == null) {
				type = "Variant";
			}
			if (!function && local.isMoved()) {
				moved.add(local.name + ": " + type + ";");
				movedLocals.add(local);
				continue;
			}
			boolean byReference = !function && local.isOutput();
			String parameterName = local.result ? "aResult" : local.name;
			if (local.result) {
				renamed.put(local, parameterName);
			}
			parameters.add((byReference ? "var " : "") + parameterName + ": " + type);
			arguments.add(local.name);
		}

		// the body: the selected text, Result renamed, re-indented one level
		String body = document.getText(range);
		List<TextRange> renames = new ArrayList<>();
		for (Local local : renamed.keySet()) {
			for (KmrPascalReferenceExpression reference : local.referencesInside) {
				renames.add(reference.getNameIdentifier().getTextRange().shiftLeft(range.getStartOffset()));
			}
		}
		renames.sort((a, b) -> Integer.compare(b.getStartOffset(), a.getStartOffset()));
		for (TextRange rename : renames) {
			body = body.substring(0, rename.getStartOffset()) + "aResult" + body.substring(rename.getEndOffset());
		}
		String originalIndent = KmrPascalRefactoringUtil.indentAt(document, range.getStartOffset());
		body = KmrPascalRefactoringUtil.reindent(body, originalIndent, unit);
		if (function) {
			body = "Result := " + body;
		}
		if (!body.endsWith(";")) {
			body += ";";
		}

		StringBuilder text = new StringBuilder();
		text.append(function ? "function " : "procedure ").append(name);
		if (!parameters.isEmpty()) {
			text.append('(').append(String.join("; ", parameters)).append(')');
		}
		if (function) {
			text.append(": ").append(resultType);
		}
		text.append(";\n");
		if (!moved.isEmpty()) {
			text.append("var\n");
			for (String declaration : moved) {
				text.append(unit).append(declaration).append('\n');
			}
		}
		text.append("begin\n").append(unit).append(body).append("\nend;\n\n");

		String call = name + (arguments.isEmpty() ? "" : "(" + String.join(", ", arguments) + ")");
		int insertAt = KmrPascalIntroduceConstantHandler.startIncludingComments(routine);
		RangeMarker insertion = document.createRangeMarker(insertAt, insertAt);
		List<TextRange> removals = declarationRemovals(movedLocals, document);
		removals.sort((a, b) -> Integer.compare(b.getStartOffset(), a.getStartOffset()));

		for (TextRange removal : removals) {
			if (removal.getStartOffset() > range.getEndOffset()) {
				document.deleteString(removal.getStartOffset(), removal.getEndOffset());
			}
		}
		document.replaceString(range.getStartOffset(), range.getEndOffset(), call);
		for (TextRange removal : removals) {
			if (removal.getStartOffset() <= range.getEndOffset()) {
				document.deleteString(removal.getStartOffset(), removal.getEndOffset());
			}
		}
		document.insertString(insertion.getStartOffset(), text.toString());
		PsiDocumentManager.getInstance(project).commitDocument(document);
	}

	/** The text to delete for each local that moves: the identifier with its comma, the declaration line, or the whole var block. */
	@NotNull
	private static List<TextRange> declarationRemovals(@NotNull List<Local> moved, @NotNull Document document)
	{
		Set<PsiElement> movedDeclarations = new HashSet<>();
		for (Local local : moved) {
			movedDeclarations.add(local.declaration);
		}
		List<TextRange> result = new ArrayList<>();
		Set<PsiElement> handledGroups = new HashSet<>();
		Set<PsiElement> handledBlocks = new HashSet<>();
		for (Local local : moved) {
			KmrPascalVarDeclaration group = (KmrPascalVarDeclaration) local.declaration.getParent();
			KmrPascalVarDeclarations block = (KmrPascalVarDeclarations) group.getParent();
			if (handledBlocks.contains(block)) {
				continue;
			}
			boolean wholeBlock = true;
			for (KmrPascalVarDeclaration g : block.getVarDeclarationList()) {
				wholeBlock &= movedDeclarations.containsAll(g.getVarIdentifierList());
			}
			if (wholeBlock) {
				handledBlocks.add(block);
				result.add(lineRange(block.getTextRange(), document));
				continue;
			}
			if (handledGroups.contains(group)) {
				continue;
			}
			handledGroups.add(group);
			List<KmrPascalVarIdentifier> identifiers = group.getVarIdentifierList();
			if (movedDeclarations.containsAll(identifiers)) {
				result.add(lineRange(group.getTextRange(), document));
				continue;
			}
			for (int i = 0; i < identifiers.size(); i++) {
				KmrPascalVarIdentifier identifier = identifiers.get(i);
				if (!movedDeclarations.contains(identifier)) {
					continue;
				}
				TextRange own = identifier.getTextRange();
				if (i + 1 < identifiers.size()) {
					result.add(TextRange.create(own.getStartOffset(), identifiers.get(i + 1).getTextRange().getStartOffset())); // "A, "
				} else {
					result.add(TextRange.create(identifiers.get(i - 1).getTextRange().getEndOffset(), own.getEndOffset())); // ", B"
				}
			}
		}
		return result;
	}

	/** The range with its line, when the element is alone on its line(s). */
	@NotNull
	private static TextRange lineRange(@NotNull TextRange range, @NotNull Document document)
	{
		int startLine = document.getLineNumber(range.getStartOffset());
		int endLine = document.getLineNumber(range.getEndOffset());
		int lineStart = document.getLineStartOffset(startLine);
		int lineEnd = document.getLineEndOffset(endLine);
		CharSequence text = document.getCharsSequence();
		boolean alone = text.subSequence(lineStart, range.getStartOffset()).toString().trim().isEmpty()
			&& text.subSequence(range.getEndOffset(), lineEnd).toString().trim().isEmpty();
		if (!alone) {
			return range;
		}
		return TextRange.create(lineStart, Math.min(lineEnd + 1, document.getTextLength()));
	}

	/** The name of the new routine: asked from the user, {@link #DEFAULT_NAME} in tests; null when cancelled. */
	@Nullable
	private static String askName(@NotNull Project project, @NotNull KmrPascalRoutineDeclaration routine)
	{
		String initial = KmrPascalRefactoringUtil.uniqueName(DEFAULT_NAME, routine);
		if (ApplicationManager.getApplication().isUnitTestMode()) {
			return initial;
		}
		return Messages.showInputDialog(project, "Name of the new routine:", "Extract Routine", Messages.getQuestionIcon(), initial, new InputValidatorEx()
		{
			@Override
			public @Nullable String getErrorText(String input)
			{
				String error = KmrPascalRenameInputValidator.validate(input.trim());
				if (error != null) {
					return error;
				}
				return KmrPascalRefactoringUtil.isNameFree(input.trim(), routine) ? null : "'" + input.trim() + "' is already used";
			}
		});
	}

	private static void showError(@NotNull Project project, @NotNull Editor editor, @NotNull String message)
	{
		CommonRefactoringUtil.showErrorHint(project, editor, message, "Cannot Perform Refactoring", null);
	}

}
