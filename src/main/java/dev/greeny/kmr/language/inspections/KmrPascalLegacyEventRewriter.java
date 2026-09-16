package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.editor.KmrPascalEventDirectiveReference;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.stubs.KmrPascalEvent;
import dev.greeny.kmr.language.stubs.KmrPascalEvents;
import dev.greeny.kmr.language.types.KmrType;
import dev.greeny.kmr.language.types.KmrTypeProvider;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import dev.greeny.kmr.language.unit.KmrPascalDirective;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migrates a handler of a deprecated event to the event's {@code ...Ex} variant: the handler is renamed (or its
 * {@code {$EVENT}} registrations point at the Ex event), parameters that turn from integer ids into enums get the enum
 * type, and every use of such a parameter in the body is converted the way {@link KmrPascalLegacyCallRewriter} converts
 * a legacy result: compared with constants, tested with {@code in}, switched on, or passed into a legacy call that is
 * rewritten along. Any other use (assignment, arithmetic, an ordinary call) leaves the handler without a fix.
 */
final class KmrPascalLegacyEventRewriter
{

	private static final Pattern REPLACEMENT = Pattern.compile("\\bUse\\s+(\\w+)");

	record Plan(@NotNull String newName, @NotNull Map<PsiFile, Set<KmrPascalLegacyCallRewriter.Edit>> edits)
	{
	}

	private KmrPascalLegacyEventRewriter()
	{
	}

	/** The Ex event a deprecated event's message names ({@code Use OnUnitAfterDiedEx instead}), or null. */
	@Nullable
	static KmrPascalEvent replacementOf(@NotNull KmrPascalEvent event, @NotNull KmrPascalEvents events, @NotNull String version)
	{
		String message = event.doc == null ? null : event.doc.getTagValue(KmrPascalDocComment.TAG_DEPRECATED);
		Matcher matcher = message == null ? null : REPLACEMENT.matcher(message);
		return matcher != null && matcher.find() ? events.findByHandlerName(version, matcher.group(1)) : null;
	}

	/** The migration of the routine handling the deprecated event, or null when it is not exact. */
	@Nullable
	static Plan plan(@NotNull KmrPascalRoutineDeclaration routine, @NotNull KmrPascalEvent event)
	{
		PsiFile file = routine.getContainingFile();
		String name = routine.getName();
		PsiElement identifier = routine.getNameIdentifier();
		KmrPascalCallable handler = KmrPascalCallable.of(routine);
		if (file == null || name == null || identifier == null || handler == null || handler.isFunction()) {
			return null;
		}
		KmrPascalEvents events = KmrPascalEvents.getInstance(routine.getProject());
		KmrPascalEvent replacement = replacementOf(event, events, KmrPascalInspectionUtil.versionOf(file));
		if (replacement == null) {
			return null;
		}
		List<KmrPascalParameterIdentifier> oldParams = event.getSignature().getParameterIdentifiers();
		List<KmrPascalParameterIdentifier> newParams = replacement.getSignature().getParameterIdentifiers();
		List<KmrPascalParameterIdentifier> ownParams = handler.getParameterIdentifiers();
		if (oldParams.size() != newParams.size() || ownParams.size() != oldParams.size()) {
			return null;
		}
		Map<PsiFile, Set<KmrPascalLegacyCallRewriter.Edit>> edits = new LinkedHashMap<>();
		Set<KmrPascalLegacyCallRewriter.Edit> own = edits.computeIfAbsent(file, f -> new LinkedHashSet<>());

		// parameters: the same type stays (with the handler's own spelling), integer id -> enum, anything else is odd
		Map<PsiElement, String> enumParameters = new LinkedHashMap<>();
		List<String> types = new ArrayList<>();
		for (int i = 0; i < ownParams.size(); i++) {
			KmrPascalParameterDeclaration ownGroup = (KmrPascalParameterDeclaration) ownParams.get(i).getParent();
			KmrPascalParameterDeclaration oldGroup = (KmrPascalParameterDeclaration) oldParams.get(i).getParent();
			KmrPascalParameterDeclaration newGroup = (KmrPascalParameterDeclaration) newParams.get(i).getParent();
			KmrType ownType = KmrTypeProvider.fromTypeSpec(ownGroup.getTypeSpec());
			KmrType oldType = KmrTypeProvider.fromTypeSpec(oldGroup.getTypeSpec());
			KmrType newType = KmrTypeProvider.fromTypeSpec(newGroup.getTypeSpec());
			if (ownType.isUnknown() || oldType.isUnknown() || newType.isUnknown() || !ownType.sameAs(oldType)
				|| !KmrPascalLegacyCallRewriter.modifierText(oldGroup).equals(KmrPascalLegacyCallRewriter.modifierText(newGroup))) {
				return null;
			}
			if (oldType.sameAs(newType)) {
				types.add(ownGroup.getTypeSpec() == null ? "" : ownGroup.getTypeSpec().getText());
			} else if (oldType.is(KmrType.Kind.INTEGER) && newType.is(KmrType.Kind.ENUM)) {
				String enumName = KmrPascalLegacyCallRewriter.enumNameOf(newType);
				if (enumName == null || !KmrPascalLegacyCallRewriter.declares(routine, enumName)) {
					return null;
				}
				enumParameters.put(ownParams.get(i), enumName);
				types.add(enumName);
			} else {
				return null;
			}
		}
		if (!enumParameters.isEmpty() && !rewriteHeader(routine, ownParams, types, own)) {
			return null;
		}

		// the registration: the conventional name, and/or {$EVENT} directives anywhere in the unit
		boolean registered = false;
		if (name.equalsIgnoreCase(event.handlerName)) {
			own.add(new KmrPascalLegacyCallRewriter.Edit(identifier.getTextRange(), replacement.handlerName));
			registered = true;
		}
		KmrPascalCompilationUnit unit = KmrPascalInspectionUtil.unitOf(file);
		for (KmrPascalCompilationUnit.Inclusion inclusion : unit.getInclusions()) {
			for (KmrPascalDirective directive : KmrPascalDirective.collect(inclusion.file)) {
				TextRange eventRange = directive.kind == KmrPascalDirective.Kind.EVENT ? registrationRange(directive, event.eventName, name) : null;
				if (eventRange != null) {
					edits.computeIfAbsent(inclusion.file, f -> new LinkedHashSet<>()).add(new KmrPascalLegacyCallRewriter.Edit(eventRange, replacement.eventName));
					registered = true;
				}
			}
		}
		if (!registered) {
			return null;
		}

		// every use of a converted parameter in the body
		KmrPascalStatementList body = routine.getStatementList();
		if (body != null) {
			for (KmrPascalIdentifierExpression use : PsiTreeUtil.collectElementsOfType(body, KmrPascalIdentifierExpression.class)) {
				String enumName = enumParameters.get(KmrPascalInspectionUtil.resolveSingle(use));
				if (enumName != null && !convertUse(use, enumName, enumParameters, own)) {
					return null;
				}
			}
		}
		return new Plan(replacement.handlerName, edits);
	}

	/** Rebuilds the parameter list with the handler's own names and the new types, grouping equal neighbours. */
	private static boolean rewriteHeader(@NotNull KmrPascalRoutineDeclaration routine, @NotNull List<KmrPascalParameterIdentifier> params, @NotNull List<String> types,
										 @NotNull Set<KmrPascalLegacyCallRewriter.Edit> edits)
	{
		PsiElement open = null;
		PsiElement close = null;
		for (PsiElement child = routine.getFirstChild(); child != null && !(child instanceof KmrPascalStatementList); child = child.getNextSibling()) {
			if (child.getNode().getElementType() == KmrPascalTypes.LBRACKET && open == null) {
				open = child;
			} else if (child.getNode().getElementType() == KmrPascalTypes.RBRACKET) {
				close = child;
			}
		}
		if (open == null || close == null) {
			return false;
		}
		StringBuilder header = new StringBuilder("(");
		int i = 0;
		while (i < params.size()) {
			KmrPascalParameterDeclaration group = (KmrPascalParameterDeclaration) params.get(i).getParent();
			int last = i;
			while (last + 1 < params.size() && params.get(last + 1).getParent() == group && types.get(last + 1).equals(types.get(i))) {
				last++;
			}
			if (i > 0) {
				header.append("; ");
			}
			if (group.getArgumentModifier() != null) {
				header.append(group.getArgumentModifier().getText()).append(' ');
			}
			for (int j = i; j <= last; j++) {
				header.append(j > i ? ", " : "").append(params.get(j).getName());
			}
			header.append(": ").append(types.get(i));
			i = last + 1;
		}
		header.append(')');
		edits.add(new KmrPascalLegacyCallRewriter.Edit(TextRange.create(open.getTextRange().getStartOffset(), close.getTextRange().getEndOffset()), header.toString()));
		return true;
	}

	/** The range of the event name in a {@code {$EVENT event:handler}} directive registering the handler, or null. */
	@Nullable
	private static TextRange registrationRange(@NotNull KmrPascalDirective directive, @NotNull String eventName, @NotNull String handlerName)
	{
		int colon = directive.argument.indexOf(':');
		if (directive.argumentRange == null || colon < 0 || !directive.argument.substring(colon + 1).trim().equalsIgnoreCase(handlerName)) {
			return null;
		}
		String eventPart = directive.argument.substring(0, colon);
		if (!eventPart.trim().equalsIgnoreCase(eventName)) {
			return null;
		}
		int start = directive.range.getStartOffset() + directive.argumentRange.getStartOffset() + (eventPart.length() - eventPart.stripLeading().length());
		return TextRange.from(start, eventPart.trim().length());
	}

	/** A use of a converted parameter: a convertible comparison/case context, or an argument of a rewritable legacy call. */
	private static boolean convertUse(@NotNull KmrPascalIdentifierExpression use, @NotNull String enumName, @NotNull Map<PsiElement, String> enumParameters,
									  @NotNull Set<KmrPascalLegacyCallRewriter.Edit> edits)
	{
		List<KmrPascalLegacyCallRewriter.Edit> local = new ArrayList<>();
		if (KmrPascalLegacyCallRewriter.convertUseContext(use, enumName, local)) {
			edits.addAll(local);
			return true;
		}
		KmrPascalReferenceElement callee = KmrPascalLegacyCallRewriter.enclosingLegacyCallee(use);
		KmrPascalLegacyCallRewriter.Plan call = callee == null ? null : KmrPascalLegacyCallRewriter.planCall(callee, null, enumParameters, 0);
		if (call == null) {
			return false;
		}
		edits.addAll(call.edits());
		return true;
	}

	/** The handler routine an inspection anchor belongs to: the routine's name, or a {$EVENT} directive's handler half. */
	@Nullable
	static KmrPascalRoutineDeclaration routineOf(@Nullable PsiElement anchor)
	{
		if (anchor instanceof PsiComment comment) {
			for (PsiReference reference : comment.getReferences()) {
				if (reference instanceof KmrPascalEventDirectiveReference directiveReference && directiveReference.isHandlerPart()
					&& reference.resolve() instanceof KmrPascalRoutineDeclaration routine) {
					return routine;
				}
			}
			return null;
		}
		return PsiTreeUtil.getParentOfType(anchor, KmrPascalRoutineDeclaration.class, false);
	}

	/** Applies {@link #plan}; recomputed at fix time from the anchor (routine name or directive). */
	static final class Fix implements LocalQuickFix
	{
		private final String newName;

		Fix(@NotNull String newName)
		{
			this.newName = newName;
		}

		@Override
		public @NotNull String getName()
		{
			return "Replace with '" + newName + "'";
		}

		@Override
		public @NotNull String getFamilyName()
		{
			return "Replace deprecated event handler with its Ex variant";
		}

		@Override
		public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor)
		{
			KmrPascalRoutineDeclaration routine = routineOf(descriptor.getPsiElement());
			KmrPascalEvent event = routine == null ? null : KmrPascalEvents.getInstance(project).findForRoutine(routine);
			Plan plan = event == null ? null : plan(routine, event);
			if (plan == null) {
				return;
			}
			for (Map.Entry<PsiFile, Set<KmrPascalLegacyCallRewriter.Edit>> entry : plan.edits().entrySet()) {
				Document document = PsiDocumentManager.getInstance(project).getDocument(entry.getKey());
				if (document != null) {
					KmrPascalLegacyCallRewriter.apply(project, document, entry.getValue());
				}
			}
		}
	}

}
