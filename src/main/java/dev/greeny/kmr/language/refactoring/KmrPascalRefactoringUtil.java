package dev.greeny.kmr.language.refactoring;

import com.intellij.application.options.CodeStyle;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.editor.KmrPascalRenameInputValidator;
import dev.greeny.kmr.language.flow.KmrConst;
import dev.greeny.kmr.language.flow.KmrConstEvaluator;
import dev.greeny.kmr.language.flow.KmrPurity;
import dev.greeny.kmr.language.flow.KmrStatements;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.resolve.KmrPascalTypeUtil;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.stubs.KmrPascalStubScope;
import dev.greeny.kmr.language.types.KmrType;
import dev.greeny.kmr.language.types.KmrTypeProvider;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared pieces of the introduce/extract refactorings: choosing expressions, finding occurrences, names, types, indentation. */
public final class KmrPascalRefactoringUtil
{

	private KmrPascalRefactoringUtil()
	{
	}

	// ---------------------------------------------------------------------------------------------------------------
	// expressions
	// ---------------------------------------------------------------------------------------------------------------

	/** The expression the selection covers exactly (surrounding whitespace ignored), or null. */
	@Nullable
	public static KmrPascalExpression expressionInRange(@NotNull PsiFile file, int start, int end)
	{
		TextRange range = trim(file, start, end);
		if (range == null) {
			return null;
		}
		PsiElement element = file.findElementAt(range.getStartOffset());
		for (PsiElement e = element; e != null && e.getTextRange().getStartOffset() >= range.getStartOffset(); e = e.getParent()) {
			if (e instanceof KmrPascalExpression && e.getTextRange().equals(range)) {
				return (KmrPascalExpression) e;
			}
			if (e instanceof KmrPascalRoutineDeclaration || e instanceof PsiFile) {
				break;
			}
		}
		return null;
	}

	/** The selection without leading/trailing whitespace; null when empty. */
	@Nullable
	public static TextRange trim(@NotNull PsiFile file, int start, int end)
	{
		CharSequence text = file.getViewProvider().getContents();
		int s = start;
		int e = Math.min(end, text.length());
		while (s < e && Character.isWhitespace(text.charAt(s))) {
			s++;
		}
		while (e > s && Character.isWhitespace(text.charAt(e - 1))) {
			e--;
		}
		return s < e ? TextRange.create(s, e) : null;
	}

	/** The expressions around the caret that are worth extracting, innermost first. */
	@NotNull
	public static List<KmrPascalExpression> extractableExpressionsAt(@NotNull PsiFile file, int offset)
	{
		PsiElement element = file.findElementAt(offset);
		if ((element == null || element instanceof PsiWhiteSpace) && offset > 0) {
			element = file.findElementAt(offset - 1);
		}
		List<KmrPascalExpression> result = new ArrayList<>();
		for (PsiElement e = element; e != null && !(e instanceof KmrPascalRoutineDeclaration) && !(e instanceof PsiFile); e = e.getParent()) {
			if (e instanceof KmrPascalExpression && isExtractable((KmrPascalExpression) e)) {
				result.add((KmrPascalExpression) e);
			}
		}
		return result;
	}

	/** Whether a variable could hold the expression's value in its place. */
	public static boolean isExtractable(@NotNull KmrPascalExpression expression)
	{
		if (PsiTreeUtil.getParentOfType(expression, KmrPascalStatementList.class) == null) {
			return false; // in a declaration
		}
		PsiElement parent = expression.getParent();
		if (parent instanceof KmrPascalCallExpression && ((KmrPascalCallExpression) parent).getExpression() == expression) {
			return false; // the callee
		}
		if (parent instanceof KmrPascalMemberExpression && expression instanceof KmrPascalIdentifierExpression) {
			return false; // the record / API object of a member access
		}
		if (parent instanceof KmrPascalAssignment && ((KmrPascalAssignment) parent).getExpressionList().get(0) == expression) {
			return false; // the assigned variable
		}
		if (parent instanceof KmrPascalForStatement && ((KmrPascalForStatement) parent).getExpressionList().get(0) == expression) {
			return false; // the loop variable
		}
		if (parent instanceof KmrPascalWithStatement) {
			return false; // the with subject: fields are addressed through it
		}
		if (parent instanceof KmrPascalFunctionArgumentList && isByReferenceArgument(expression, (KmrPascalFunctionArgumentList) parent)) {
			return false;
		}
		if (expression instanceof KmrPascalIdentifierExpression) {
			PsiElement target = KmrPascalTypeUtil.resolveSingle(expression);
			if (target instanceof KmrPascalTypeDeclaration || target instanceof KmrPascalProcedureDeclaration) {
				return false; // a type or a procedure name
			}
			if (target instanceof KmrPascalVarIdentifier && KmrPascalStubLibrary.isStubFile(target.getContainingFile())) {
				return false; // Actions / States / Utils
			}
		}
		KmrType type = KmrTypeProvider.typeOf(expression);
		return !type.is(KmrType.Kind.VOID);
	}

	private static boolean isByReferenceArgument(@NotNull KmrPascalExpression argument, @NotNull KmrPascalFunctionArgumentList arguments)
	{
		PsiElement call = arguments.getParent();
		if (!(call instanceof KmrPascalCallExpression)) {
			return false;
		}
		KmrPascalCallable callable = KmrPascalCallable.of(KmrPascalTypeUtil.resolveSingle(((KmrPascalCallExpression) call).getExpression()));
		int index = arguments.getExpressionList().indexOf(argument);
		if (callable == null || index < 0 || index >= callable.getParameterCount()) {
			return false;
		}
		PsiElement group = callable.getParameterIdentifiers().get(index).getParent();
		return group instanceof KmrPascalParameterDeclaration && KmrPurity.isByReference((KmrPascalParameterDeclaration) group);
	}

	/** Extractable expressions in the scope that read the same as the given one (case and whitespace ignored), in text order. */
	@NotNull
	public static List<KmrPascalExpression> occurrences(@NotNull PsiElement scope, @NotNull KmrPascalExpression expression)
	{
		String key = normalize(expression.getText());
		List<KmrPascalExpression> result = new ArrayList<>();
		for (KmrPascalExpression candidate : PsiTreeUtil.findChildrenOfType(scope, KmrPascalExpression.class)) {
			if (candidate.getClass() == expression.getClass() && normalize(candidate.getText()).equals(key) && isExtractable(candidate)) {
				result.add(candidate);
			}
		}
		if (result.isEmpty() && PsiTreeUtil.isAncestor(scope, expression, false)) {
			result.add(expression);
		}
		return result;
	}

	@NotNull
	public static String normalize(@NotNull String text)
	{
		return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// names and types
	// ---------------------------------------------------------------------------------------------------------------

	/** A readable name for a variable holding the expression: the called function, the field, the kind of literal. */
	@NotNull
	public static String suggestVariableName(@NotNull KmrPascalExpression expression)
	{
		KmrPascalExpression e = expression;
		while (e instanceof KmrPascalParenExpression && ((KmrPascalParenExpression) e).getExpression() != null) {
			e = ((KmrPascalParenExpression) e).getExpression();
		}
		if (e instanceof KmrPascalCallExpression) {
			KmrPascalExpression callee = ((KmrPascalCallExpression) e).getExpression();
			if (callee instanceof KmrPascalReferenceExpression) {
				return capitalize(((KmrPascalReferenceExpression) callee).getReferenceName());
			}
		}
		if (e instanceof KmrPascalMemberExpression) {
			return capitalize(((KmrPascalMemberExpression) e).getReferenceName());
		}
		if (e instanceof KmrPascalIndexExpression) {
			List<KmrPascalExpression> children = ((KmrPascalIndexExpression) e).getExpressionList();
			if (!children.isEmpty() && children.get(0) instanceof KmrPascalReferenceExpression) {
				return singular(capitalize(((KmrPascalReferenceExpression) children.get(0)).getReferenceName()));
			}
		}
		if (e instanceof KmrPascalIdentifierExpression) {
			return capitalize(((KmrPascalIdentifierExpression) e).getReferenceName()) + "Value";
		}
		KmrType type = KmrTypeProvider.typeOf(e);
		switch (type.kind) {
			case BOOLEAN: return "Flag";
			case STRING: case CHAR: return "Text";
			case INTEGER: return type.valueKind != null ? capitalize(type.valueKind.label.replaceAll("[^A-Za-z0-9]", "")) : "Value";
			case REAL: return "Number";
			case ENUM: case RECORD: return capitalize(type.toString().replaceFirst("^TKM|^T(?=[A-Z])", ""));
			default: return "Value";
		}
	}

	/** {@code MAX_UNITS} style name for a constant: the words of a string literal, else the kind of value. */
	@NotNull
	public static String suggestConstantName(@NotNull KmrPascalExpression expression)
	{
		KmrConst value = KmrConstEvaluator.evaluate(expression);
		if (value != null && value.is(KmrConst.Kind.STRING)) {
			String[] words = value.stringValue().replaceAll("[^A-Za-z0-9]+", " ").trim().split(" ");
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < Math.min(3, words.length) && !words[i].isEmpty(); i++) {
				sb.append(sb.length() > 0 ? "_" : "").append(words[i].toUpperCase(Locale.ROOT));
			}
			if (sb.length() > 0 && !Character.isDigit(sb.charAt(0))) {
				return sb.toString();
			}
			return "TEXT";
		}
		if (value != null && value.is(KmrConst.Kind.BOOLEAN)) {
			return "FLAG";
		}
		if (value != null && value.is(KmrConst.Kind.ENUM)) {
			return toScreamingCase(value.toString().replaceFirst("^[a-z]+(?=[A-Z])", ""));
		}
		return "VALUE";
	}

	@NotNull
	private static String toScreamingCase(@NotNull String camel)
	{
		return camel.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "_").toUpperCase(Locale.ROOT);
	}

	@NotNull
	static String capitalize(@NotNull String name)
	{
		return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	@NotNull
	private static String singular(@NotNull String name)
	{
		return name.length() > 3 && name.endsWith("s") && !name.endsWith("ss") ? name.substring(0, name.length() - 1) : name;
	}

	/** The name, or the name with a number appended, that no declaration visible at the place uses. */
	@NotNull
	public static String uniqueName(@NotNull String base, @NotNull PsiElement place)
	{
		String name = KmrPascalRenameInputValidator.validate(base) == null ? base : base + "Value";
		String candidate = name;
		for (int i = 1; !isNameFree(candidate, place); i++) {
			candidate = name + i;
		}
		return candidate;
	}

	/** Whether no declaration visible at the place (locals, unit globals, API, built-in types) has the name. */
	public static boolean isNameFree(@NotNull String name, @NotNull PsiElement place)
	{
		if (KmrPascalRenameInputValidator.validate(name) != null || KmrTypeProvider.builtinType(name) != null) {
			return false;
		}
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(place, KmrPascalRoutineDeclaration.class, false);
		if (routine != null) {
			if (name.equalsIgnoreCase(routine.getName()) || name.equalsIgnoreCase("Result")) {
				return false;
			}
			for (KmrPascalParameterDeclaration parameter : routine.getParameters()) {
				for (KmrPascalParameterIdentifier identifier : parameter.getParameterIdentifierList()) {
					if (name.equalsIgnoreCase(identifier.getName())) {
						return false;
					}
				}
			}
			for (PsiElement child : routine.getChildren()) {
				for (KmrPascalNamedElement declaration : KmrPascalCompilationUnit.topLevelDeclarations(child)) {
					if (name.equalsIgnoreCase(declaration.getName())) {
						return false;
					}
				}
			}
		}
		PsiFile file = place.getContainingFile();
		KmrPascalCompilationUnit unit = KmrPascalResolveContextService.getInstance(place.getProject()).getUnitFor(file.getOriginalFile());
		if (!unit.findGlobals(name).isEmpty()) {
			return false;
		}
		return KmrPascalStubScope.getInstance(place.getProject()).findDeclarations(file, name).isEmpty();
	}

	/** Valid PascalScript for the expression's type: the declared type name where there is one, else the built-in, else Variant. */
	@NotNull
	public static String typeText(@NotNull KmrPascalExpression expression)
	{
		KmrPascalTypeElement declared = KmrPascalTypeUtil.typeElementOf(expression);
		if (declared instanceof KmrPascalFunctionType) {
			declared = KmrPascalTypeUtil.typeElementOf(((KmrPascalFunctionType) declared).getTypeSpec()); // States.GameTime: the result
		}
		if (declared instanceof KmrPascalTypeReference && !isHiddenAlias((KmrPascalTypeReference) declared)) {
			return declared.getText();
		}
		KmrType type = KmrTypeProvider.typeOf(expression);
		switch (type.kind) {
			case INTEGER: return "Integer";
			case REAL: return "Single";
			case BOOLEAN: return "Boolean";
			case STRING: return "String";
			case CHAR: return "Char";
			case ENUM: case RECORD:
				String name = type.toString();
				return name.equals("enum") || name.equals("record") ? "Variant" : name;
			default:
				if (declared != null && !(declared instanceof KmrPascalTypeReference) && !declared.getText().contains("\n")) {
					return declared.getText(); // array of Integer, set of TKMUnitType
				}
				return "Variant";
		}
	}

	/** The kind aliases of the API stubs ({@code TKMUnitID = Integer} marked {@code @hidden}) are not for user code. */
	private static boolean isHiddenAlias(@NotNull KmrPascalTypeReference reference)
	{
		PsiElement target = KmrPascalTypeUtil.resolveSingle(reference);
		if (!(target instanceof KmrPascalTypeDeclaration) || !KmrPascalStubLibrary.isStubFile(target.getContainingFile())) {
			return false;
		}
		KmrPascalDocComment doc = KmrPascalDocComment.of(target);
		return doc != null && doc.hasTag(KmrPascalDocComment.TAG_HIDDEN);
	}

	/** The declared type text of a variable/parameter/field, or null. */
	@Nullable
	public static String declaredTypeText(@NotNull PsiElement declaration)
	{
		if (declaration instanceof KmrPascalTypedIdentifier) {
			KmrPascalTypeSpec type = ((KmrPascalTypedIdentifier) declaration).getType();
			return type == null ? null : type.getText();
		}
		if (declaration instanceof KmrPascalFunctionDeclaration) {
			KmrPascalTypeSpec type = ((KmrPascalFunctionDeclaration) declaration).getTypeSpec();
			return type == null ? null : type.getText();
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// text layout
	// ---------------------------------------------------------------------------------------------------------------

	/** The leading whitespace of the line containing the offset. */
	@NotNull
	public static String indentAt(@NotNull Document document, int offset)
	{
		int line = document.getLineNumber(offset);
		int start = document.getLineStartOffset(line);
		CharSequence text = document.getCharsSequence();
		int i = start;
		while (i < document.getLineEndOffset(line) && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
			i++;
		}
		return text.subSequence(start, i).toString();
	}

	/** Whether nothing but whitespace precedes the offset on its line. */
	public static boolean isFirstOnLine(@NotNull Document document, int offset)
	{
		int start = document.getLineStartOffset(document.getLineNumber(offset));
		return document.getCharsSequence().subSequence(start, offset).toString().trim().isEmpty();
	}

	/** One indentation level according to the code style (a tab by default). */
	@NotNull
	public static String indentUnit(@NotNull PsiFile file)
	{
		CommonCodeStyleSettings.IndentOptions options = CodeStyle.getIndentOptions(file);
		return options.USE_TAB_CHARACTER ? "\t" : " ".repeat(Math.max(1, options.INDENT_SIZE));
	}

	/** The indentation the routine's body actually uses (its first statement relative to {@code begin}), else the code style's. */
	@NotNull
	public static String indentUnit(@NotNull KmrPascalRoutineDeclaration routine, @NotNull Document document)
	{
		KmrPascalStatementList body = routine.getStatementList();
		List<PsiElement> statements = body == null ? List.of() : KmrStatements.statementsOf(body);
		if (body != null && !statements.isEmpty()) {
			int begin = body.getTextRange().getStartOffset();
			int first = statements.get(0).getTextRange().getStartOffset();
			if (isFirstOnLine(document, begin) && isFirstOnLine(document, first) && document.getLineNumber(begin) < document.getLineNumber(first)) {
				String beginIndent = indentAt(document, begin);
				String firstIndent = indentAt(document, first);
				if (firstIndent.length() > beginIndent.length() && firstIndent.startsWith(beginIndent)) {
					return firstIndent.substring(beginIndent.length());
				}
			}
		}
		return indentUnit(routine.getContainingFile());
	}

	/** Shifts every line of the text from the old indentation to the new one. */
	@NotNull
	public static String reindent(@NotNull String text, @NotNull String oldIndent, @NotNull String newIndent)
	{
		String[] lines = text.split("\n", -1);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (i > 0) {
				sb.append('\n');
				if (line.startsWith(oldIndent)) {
					line = newIndent + line.substring(oldIndent.length());
				} else if (!line.trim().isEmpty()) {
					line = newIndent + line.stripLeading();
				}
			}
			sb.append(line);
		}
		return sb.toString();
	}

}
