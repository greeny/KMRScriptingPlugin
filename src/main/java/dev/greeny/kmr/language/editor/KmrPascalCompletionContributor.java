package dev.greeny.kmr.language.editor;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import dev.greeny.kmr.language.KmrPascalLanguage;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.resolve.KmrPascalTypeUtil;
import dev.greeny.kmr.language.stubs.KmrPascalEvent;
import dev.greeny.kmr.language.stubs.KmrPascalEvents;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.stubs.KmrPascalStubScope;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

/**
 * Code completion: members after a dot, everything in scope (locals, unit globals, API stubs of the target version),
 * type names, keywords by position, and event handler templates at file level.
 */
public class KmrPascalCompletionContributor extends CompletionContributor
{

	static final List<String> STATEMENT_KEYWORDS = List.of("begin", "if", "while", "for", "repeat", "case", "with", "exit", "break", "continue");
	static final List<String> EXPRESSION_KEYWORDS = List.of("not", "and", "or", "xor", "div", "mod", "shl", "shr", "in", "true", "false", "nil");
	static final List<String> TOP_LEVEL_KEYWORDS = List.of("procedure", "function", "var", "const", "type");
	static final List<String> LOCAL_DECLARATION_KEYWORDS = List.of("var", "const", "type", "begin");
	static final List<String> TYPE_KEYWORDS = List.of("array", "record", "set", "procedure", "function");
	static final List<String> BUILTIN_TYPES = dev.greeny.kmr.language.psi.KmrPascalBuiltins.TYPES;

	private static final double PRIORITY_LOCAL = 30;
	private static final double PRIORITY_MEMBER = 20;
	private static final double PRIORITY_GLOBAL = 10;
	private static final double PRIORITY_STUB = 5;
	private static final double PRIORITY_KEYWORD = 1;

	public KmrPascalCompletionContributor()
	{
		extend(CompletionType.BASIC, PlatformPatterns.psiElement(KmrPascalTypes.IDENTIFIER).withLanguage(KmrPascalLanguage.INSTANCE), new CompletionProvider<>()
		{
			@Override
			protected void addCompletions(@NotNull CompletionParameters parameters, @NotNull ProcessingContext context, @NotNull CompletionResultSet result)
			{
				// Pascal is case-insensitive, so "actions.showm" must find "ShowMsg" regardless of the IDE's case setting
				complete(parameters, result.withPrefixMatcher(new CamelHumpMatcher(result.getPrefixMatcher().getPrefix(), false)));
			}
		});
	}

	private static void complete(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result)
	{
		PsiElement position = parameters.getPosition();
		PsiElement parent = position.getParent();

		if (parent instanceof KmrPascalMemberExpression) {
			KmrPascalExpression qualifier = ((KmrPascalMemberExpression) parent).getQualifier();
			if (qualifier != null) {
				addMembers(qualifier, result);
			}
			return;
		}
		if (parent instanceof KmrPascalTypeReference) {
			addTypes(position, result);
			return;
		}
		if (parent instanceof KmrPascalIdentifierExpression) {
			addScope(position, result);
			addKeywords(isStatementStart(parent) ? STATEMENT_KEYWORDS : EXPRESSION_KEYWORDS, result);
			return;
		}
		if (parent instanceof KmrPascalRoutineDeclaration && position == ((KmrPascalRoutineDeclaration) parent).getNameIdentifier()) {
			addEventHandlers(position, result, false);
			return;
		}
		PsiElement anchor = parent;
		while (anchor instanceof PsiErrorElement) {
			anchor = anchor.getParent();
		}
		if (anchor instanceof PsiFile) {
			if (followsRoutineWithoutBody(position)) {
				addKeywords(LOCAL_DECLARATION_KEYWORDS, result);
			}
			addKeywords(TOP_LEVEL_KEYWORDS, result);
			addEventHandlers(position, result, true);
		} else if (anchor instanceof KmrPascalRoutineDeclaration) {
			addKeywords(LOCAL_DECLARATION_KEYWORDS, result);
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static void addMembers(@NotNull KmrPascalExpression qualifier, @NotNull CompletionResultSet result)
	{
		KmrPascalRecordType record = KmrPascalTypeUtil.recordTypeOf(qualifier);
		if (record == null) {
			return;
		}
		for (KmrPascalFieldIdentifier field : PsiTreeUtil.findChildrenOfType(record, KmrPascalFieldIdentifier.class)) {
			if (field.getName() != null) {
				result.addElement(PrioritizedLookupElement.withPriority(declarationItem(field), PRIORITY_MEMBER));
			}
		}
	}

	private static void addTypes(@NotNull PsiElement position, @NotNull CompletionResultSet result)
	{
		Set<String> seen = new HashSet<>();
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(position, KmrPascalRoutineDeclaration.class);
		if (routine != null) {
			for (PsiElement child : routine.getChildren()) {
				for (KmrPascalNamedElement local : KmrPascalCompilationUnit.topLevelDeclarations(child)) {
					if (local instanceof KmrPascalTypeDeclaration && local.getName() != null && seen.add(local.getName().toLowerCase())) {
						result.addElement(PrioritizedLookupElement.withPriority(declarationItem(local), PRIORITY_LOCAL));
					}
				}
			}
		}
		for (KmrPascalNamedElement declaration : visibleDeclarations(position)) {
			if (declaration instanceof KmrPascalTypeDeclaration && declaration.getName() != null && seen.add(declaration.getName().toLowerCase())) {
				result.addElement(PrioritizedLookupElement.withPriority(declarationItem(declaration), PRIORITY_GLOBAL));
			}
		}
		for (String type : BUILTIN_TYPES) {
			result.addElement(PrioritizedLookupElement.withPriority(
				LookupElementBuilder.create(type).withIcon(AllIcons.Nodes.Type).withTypeText("built-in type", true), PRIORITY_GLOBAL));
		}
		addKeywords(TYPE_KEYWORDS, result);
	}

	private static void addScope(@NotNull PsiElement position, @NotNull CompletionResultSet result)
	{
		Set<String> seen = new HashSet<>();
		// with statement fields
		for (KmrPascalWithStatement with = PsiTreeUtil.getParentOfType(position, KmrPascalWithStatement.class); with != null;
			 with = PsiTreeUtil.getParentOfType(with, KmrPascalWithStatement.class)) {
			for (KmrPascalExpression subject : with.getExpressionList()) {
				if (PsiTreeUtil.isAncestor(subject, position, false)) {
					continue;
				}
				KmrPascalRecordType record = KmrPascalTypeUtil.recordTypeOf(subject);
				if (record != null) {
					for (KmrPascalFieldIdentifier field : PsiTreeUtil.findChildrenOfType(record, KmrPascalFieldIdentifier.class)) {
						if (field.getName() != null && seen.add(field.getName().toLowerCase())) {
							result.addElement(PrioritizedLookupElement.withPriority(declarationItem(field), PRIORITY_LOCAL));
						}
					}
				}
			}
		}
		// enclosing routine
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(position, KmrPascalRoutineDeclaration.class);
		if (routine != null) {
			List<KmrPascalNamedElement> locals = new ArrayList<>();
			for (KmrPascalParameterDeclaration parameter : routine.getParameters()) {
				locals.addAll(parameter.getParameterIdentifierList());
			}
			for (PsiElement child : routine.getChildren()) {
				locals.addAll(KmrPascalCompilationUnit.topLevelDeclarations(child));
			}
			for (KmrPascalNamedElement local : locals) {
				if (local.getName() != null && seen.add(local.getName().toLowerCase())) {
					result.addElement(PrioritizedLookupElement.withPriority(declarationItem(local), PRIORITY_LOCAL));
				}
			}
			if (routine instanceof KmrPascalFunctionDeclaration && seen.add("result")) {
				result.addElement(PrioritizedLookupElement.withPriority(
					LookupElementBuilder.create("Result").withIcon(AllIcons.Nodes.Variable)
						.withTypeText(typeText(((KmrPascalFunctionDeclaration) routine).getTypeSpec()), true), PRIORITY_LOCAL));
			}
		}
		// unit globals, then stubs
		for (KmrPascalNamedElement declaration : visibleDeclarations(position)) {
			if (declaration.getName() != null && seen.add(declaration.getName().toLowerCase())) {
				boolean stub = KmrPascalStubLibrary.isStubFile(declaration.getContainingFile());
				result.addElement(PrioritizedLookupElement.withPriority(declarationItem(declaration), stub ? PRIORITY_STUB : PRIORITY_GLOBAL));
			}
		}
	}

	/** Unit globals followed by the visible stub declarations of the target version. */
	@NotNull
	private static List<KmrPascalNamedElement> visibleDeclarations(@NotNull PsiElement position)
	{
		PsiFile file = position.getContainingFile().getOriginalFile();
		List<KmrPascalNamedElement> result = new ArrayList<>();
		for (List<KmrPascalNamedElement> group : KmrPascalResolveContextService.getInstance(file.getProject()).getUnitFor(file).getGlobals().values()) {
			result.addAll(group);
		}
		KmrPascalStubScope stubs = KmrPascalStubScope.getInstance(file.getProject());
		for (List<KmrPascalStubScope.Declaration> group : stubs.declarations(stubs.versionFor(file)).values()) {
			for (KmrPascalStubScope.Declaration declaration : group) {
				if (!declaration.hidden) {
					result.add(declaration.element);
				}
			}
		}
		return result;
	}

	private static void addEventHandlers(@NotNull PsiElement position, @NotNull CompletionResultSet result, boolean withProcedureKeyword)
	{
		PsiFile file = position.getContainingFile().getOriginalFile();
		Set<String> implemented = new HashSet<>();
		for (List<KmrPascalNamedElement> group : KmrPascalResolveContextService.getInstance(file.getProject()).getUnitFor(file).getGlobals().values()) {
			for (KmrPascalNamedElement global : group) {
				if (global instanceof KmrPascalRoutineDeclaration && global.getName() != null) {
					implemented.add(global.getName().toLowerCase());
				}
			}
		}
		String version = KmrPascalStubScope.getInstance(file.getProject()).versionFor(file);
		for (KmrPascalEvent event : KmrPascalEvents.getInstance(file.getProject()).getEvents(version)) {
			if (implemented.contains(event.handlerName.toLowerCase())) {
				continue;
			}
			LookupElementBuilder item = LookupElementBuilder.create(event.field, event.handlerName)
				.withIcon(AllIcons.Nodes.Method)
				.withTailText(event.getSignature().getSignatureText(), true)
				.withTypeText("event", true)
				.withInsertHandler(new EventTemplateInsertHandler(event, withProcedureKeyword));
			result.addElement(PrioritizedLookupElement.withPriority(item, PRIORITY_GLOBAL));
		}
	}

	private static void addKeywords(@NotNull List<String> keywords, @NotNull CompletionResultSet result)
	{
		for (String keyword : keywords) {
			result.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(keyword).bold(), PRIORITY_KEYWORD));
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** Lookup item for a declaration: icon by kind, signature or type as tail/type text, strikeout when deprecated. */
	@NotNull
	static LookupElement declarationItem(@NotNull KmrPascalNamedElement declaration)
	{
		String name = Objects.requireNonNull(declaration.getName());
		LookupElementBuilder item = LookupElementBuilder.create(declaration, name).withIcon(iconFor(declaration));
		KmrPascalCallable callable = KmrPascalCallable.of(declaration);
		if (callable != null) {
			item = item.withTailText(parameterText(callable), true)
				.withTypeText(callable.isFunction() ? dev.greeny.kmr.language.types.KmrTypePresenter.returnTypeText(callable, true) : "procedure", true);
			if (callable.getParameterCount() > 0) {
				item = item.withInsertHandler(ParenthesesInsertHandler.INSTANCE);
			}
		} else if (declaration instanceof KmrPascalTypedIdentifier) {
			item = item.withTypeText(dev.greeny.kmr.language.types.KmrTypePresenter.typeText((KmrPascalTypedIdentifier) declaration, true), true);
		} else if (declaration instanceof KmrPascalConstantDeclaration) {
			KmrPascalExpression value = ((KmrPascalConstantDeclaration) declaration).getExpression();
			item = item.withTypeText(value == null ? "const" : "= " + value.getText(), true);
		} else if (declaration instanceof KmrPascalTypeDeclaration) {
			item = item.withTypeText("type", true);
		} else if (declaration instanceof KmrPascalEnumValue) {
			KmrPascalTypeDeclaration enumType = PsiTreeUtil.getParentOfType(declaration, KmrPascalTypeDeclaration.class);
			item = item.withTypeText(enumType == null ? "enum value" : enumType.getName(), true);
		}
		KmrPascalDocComment doc = KmrPascalDocComment.of(declaration);
		if (doc != null && doc.hasTag(KmrPascalDocComment.TAG_DEPRECATED)) {
			item = item.withStrikeoutness(true);
		}
		return item;
	}

	@NotNull
	private static String parameterText(@NotNull KmrPascalCallable callable)
	{
		StringBuilder sb = new StringBuilder("(");
		List<KmrPascalParameterDeclaration> groups = callable.getParameterDeclarations();
		for (int i = 0; i < groups.size(); i++) {
			if (i > 0) {
				sb.append("; ");
			}
			sb.append(dev.greeny.kmr.language.types.KmrTypePresenter.parameterText(groups.get(i), true));
		}
		return sb.append(')').toString();
	}

	/** Type text with the kinds of the API in words ({@code Integer (unit ID)}), never a hidden alias name. */
	@NotNull
	static String typeText(@Nullable KmrPascalTypeSpec type)
	{
		return dev.greeny.kmr.language.types.KmrTypePresenter.typeText(type);
	}

	@NotNull
	static Icon iconFor(@NotNull PsiElement declaration)
	{
		return dev.greeny.kmr.language.KmrPascalIcons.forDeclaration(declaration);
	}

	/** "procedure P; &lt;caret&gt;": the parser recovered at file level, but the routine header before us has no body yet. */
	private static boolean followsRoutineWithoutBody(@NotNull PsiElement element)
	{
		PsiElement top = element;
		while (top.getParent() != null && !(top.getParent() instanceof PsiFile)) {
			top = top.getParent();
		}
		// skip whitespace, comments and whatever error recovery left behind (error elements, dummy blocks) until a
		// real top-level declaration
		PsiElement previous = top.getPrevSibling();
		while (previous != null && !(previous instanceof KmrPascalRoutineDeclaration) && !(previous instanceof KmrPascalConstantDeclarations)
			&& !(previous instanceof KmrPascalVarDeclarations) && !(previous instanceof KmrPascalTypeDeclarations)) {
			previous = previous.getPrevSibling();
		}
		return previous instanceof KmrPascalRoutineDeclaration && ((KmrPascalRoutineDeclaration) previous).getStatementList() == null;
	}

	private static boolean isStatementStart(@NotNull PsiElement identifierExpression)
	{
		PsiElement parent = identifierExpression.getParent();
		return parent instanceof KmrPascalExpressionStatement
			|| parent instanceof KmrPascalAssignment && parent.getFirstChild() == identifierExpression;
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** Inserts "()" after a call with parameters, places the caret inside and opens parameter info. */
	private static final class ParenthesesInsertHandler implements InsertHandler<LookupElement>
	{
		static final ParenthesesInsertHandler INSTANCE = new ParenthesesInsertHandler();

		@Override
		public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item)
		{
			Editor editor = context.getEditor();
			Document document = editor.getDocument();
			int offset = context.getTailOffset();
			if (offset < document.getTextLength() && document.getCharsSequence().charAt(offset) == '(') {
				editor.getCaretModel().moveToOffset(offset + 1);
			} else {
				document.insertString(offset, "()");
				editor.getCaretModel().moveToOffset(offset + 1);
			}
			AutoPopupController.getInstance(context.getProject()).autoPopupParameterInfo(editor, null);
		}
	}

	/**
	 * Expands an event into a handler skeleton. At file level the whole "procedure Name(params);" header is inserted;
	 * after the "procedure" keyword only the rest. The body is added when the line has nothing after the caret.
	 */
	private static final class EventTemplateInsertHandler implements InsertHandler<LookupElement>
	{
		private final KmrPascalEvent event;
		private final boolean withProcedureKeyword;

		EventTemplateInsertHandler(@NotNull KmrPascalEvent event, boolean withProcedureKeyword)
		{
			this.event = event;
			this.withProcedureKeyword = withProcedureKeyword;
		}

		@Override
		public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item)
		{
			Document document = context.getDocument();
			int start = context.getStartOffset();
			int end = context.getTailOffset();
			int lineEnd = document.getLineEndOffset(document.getLineNumber(end));
			String rest = document.getText(TextRange.create(end, lineEnd)).trim();
			boolean addBody = rest.isEmpty() || rest.equals(";");
			if (addBody) {
				end = lineEnd;
			}
			// plain types: the header must compile, so the kind words are left out
			String header = (withProcedureKeyword ? "procedure " : "") + event.handlerName + event.getSignature().getSignatureText(false) + ";";
			String bodyPrefix = "\nbegin\n  ";
			String text = addBody ? header + bodyPrefix + "\nend;" : header;
			document.replaceString(start, end, text);
			context.getEditor().getCaretModel().moveToOffset(addBody ? start + header.length() + bodyPrefix.length() : start + text.length());
			context.commitDocument();
		}
	}

}
