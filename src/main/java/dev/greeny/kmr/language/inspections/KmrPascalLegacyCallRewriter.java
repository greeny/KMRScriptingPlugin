package dev.greeny.kmr.language.inspections;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.resolve.KmrPascalTypeUtil;
import dev.greeny.kmr.language.stubs.KmrPascalLegacyIds;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.stubs.KmrPascalStubScope;
import dev.greeny.kmr.language.types.KmrType;
import dev.greeny.kmr.language.types.KmrTypeProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites a call of a deprecated API member to its {@code ...Ex} sibling: {@code Actions.GiveUnit(0, 14, X, Y, 4)}
 * becomes {@code Actions.GiveUnitEx(0, utMilitia, X, Y, dirS)}. A rewrite is offered only when it is exact:
 * <ul>
 * <li>parameters pair up by position with the same type, or an integer id becomes an enum ({@link KmrPascalLegacyIds})
 * and the argument is a constant (a number, a named constant, or another rewritable legacy call returning that enum),
 * or a {@code TByteSet} becomes a set of that enum and the argument is a set literal of such constants;</li>
 * <li>a result that turns from integer id into enum is only compared for (in)equality with a constant, tested with
 * {@code in} against a literal set of constants, switched on in a {@code case} with constant labels, or passed straight
 * into another legacy call being rewritten;</li>
 * <li>{@code HouseAddBuildingProgress} (one step) becomes {@code HouseAddBuildingProgressEx(..., 1)};
 * {@code GiveHouseSite(..., False)} becomes {@code GiveHouseSiteEx(..., 0, 0)}.</li>
 * </ul>
 * An event handler migration ({@link KmrPascalLegacyEventRewriter}) passes the handler parameters that turn into enums
 * as {@code enumParameters}: a plain reference to one of them already is the enum value the Ex variant wants.
 * The edits are applied to the document text, so the call keeps its formatting.
 */
final class KmrPascalLegacyCallRewriter
{

	/** The old functions where {@code -1} meant "any type" (the Ex variants take the {@code xxAny} member). */
	private static final Set<String> MINUS_ONE_IS_ANY = Set.of("closestgroup", "closesthouse", "closestunit");
	private static final int MAX_NESTING = 4;
	private static final int MAX_CONSTANT_DEPTH = 8;

	record Edit(@NotNull TextRange range, @NotNull String text)
	{
	}

	record Plan(@NotNull String newName, @NotNull List<Edit> edits)
	{
	}

	private KmrPascalLegacyCallRewriter()
	{
	}

	/**
	 * The rewrite for a call whose callee is the given deprecated reference, or null when none is exact. A legacy call
	 * whose result feeds another legacy call gets the rewrite of that outer call (which includes its own).
	 */
	@Nullable
	static Plan plan(@NotNull KmrPascalReferenceElement callee)
	{
		return plan(callee, Map.of(), 0);
	}

	@Nullable
	private static Plan plan(@NotNull KmrPascalReferenceElement callee, @NotNull Map<PsiElement, String> enumParameters, int depth)
	{
		Plan own = planCall(callee, null, enumParameters, depth);
		if (own != null || depth >= MAX_NESTING) {
			return own;
		}
		KmrPascalReferenceElement outer = enclosingLegacyCallee(callee.getParent());
		return outer == null ? null : plan(outer, enumParameters, depth + 1);
	}

	/**
	 * The rewrite of one call; with {@code expectedResultEnum} the call is an argument of an outer rewrite and its Ex
	 * variant must return exactly that enum, otherwise the result's own context is converted.
	 */
	@Nullable
	static Plan planCall(@NotNull KmrPascalReferenceElement callee, @Nullable String expectedResultEnum, @NotNull Map<PsiElement, String> enumParameters, int depth)
	{
		if (depth > MAX_NESTING || !(callee.getParent() instanceof KmrPascalCallExpression call) || call.getExpression() != callee) {
			return null;
		}
		KmrPascalFieldIdentifier target = deprecatedStubMember(callee);
		String name = target == null ? null : target.getName();
		KmrPascalRecordType record = target == null ? null : PsiTreeUtil.getParentOfType(target, KmrPascalRecordType.class);
		if (name == null || record == null) {
			return null;
		}
		List<KmrPascalFieldIdentifier> siblings = KmrPascalTypeUtil.findFields(record, name + "Ex");
		KmrPascalCallable old = KmrPascalCallable.of(target);
		KmrPascalCallable ex = siblings.size() == 1 ? KmrPascalCallable.of(siblings.get(0)) : null;
		if (old == null || ex == null) {
			return null;
		}
		List<Edit> edits = new ArrayList<>();
		edits.add(new Edit(callee.getNameIdentifier().getTextRange(), name + "Ex"));
		if (!convertArguments(call, name, old, ex, enumParameters, edits, depth) || !convertResult(call, old, ex, expectedResultEnum, edits)) {
			return null;
		}
		return new Plan(name + "Ex", edits);
	}

	/** The resolved stub member when it is deprecated, else null. */
	@Nullable
	static KmrPascalFieldIdentifier deprecatedStubMember(@NotNull KmrPascalReferenceElement callee)
	{
		PsiElement target = KmrPascalInspectionUtil.resolveSingle(callee);
		if (!(target instanceof KmrPascalFieldIdentifier field) || !KmrPascalStubLibrary.isStubFile(field.getContainingFile())) {
			return null;
		}
		KmrPascalDocComment doc = KmrPascalDocComment.of(field);
		return doc != null && doc.hasTag(KmrPascalDocComment.TAG_DEPRECATED) ? field : null;
	}

	/** The callee of the legacy call the expression is a direct argument of (parentheses aside), or null. */
	@Nullable
	static KmrPascalReferenceElement enclosingLegacyCallee(@NotNull PsiElement expression)
	{
		PsiElement context = expression;
		while (context.getParent() instanceof KmrPascalParenExpression) {
			context = context.getParent();
		}
		if (context.getParent() instanceof KmrPascalFunctionArgumentList arguments && arguments.getParent() instanceof KmrPascalCallExpression outer
			&& outer.getExpression() instanceof KmrPascalReferenceElement outerCallee && deprecatedStubMember(outerCallee) != null) {
			return outerCallee;
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// arguments
	// ---------------------------------------------------------------------------------------------------------------

	private static boolean convertArguments(@NotNull KmrPascalCallExpression call, @NotNull String name, @NotNull KmrPascalCallable old, @NotNull KmrPascalCallable ex,
											@NotNull Map<PsiElement, String> enumParameters, @NotNull List<Edit> edits, int depth)
	{
		KmrPascalFunctionArgumentList arguments = call.getFunctionArgumentList();
		List<KmrPascalExpression> values = arguments == null ? List.of() : arguments.getExpressionList();
		List<KmrPascalParameterIdentifier> oldParams = old.getParameterIdentifiers();
		List<KmrPascalParameterIdentifier> newParams = ex.getParameterIdentifiers();
		if (values.size() != oldParams.size() || values.isEmpty()) {
			return false;
		}
		// GiveHouseSite's trailing "add all materials" flag became explicit wood and stone amounts
		boolean houseSite = name.equalsIgnoreCase("GiveHouseSite") && oldParams.size() == 5 && newParams.size() == 6;
		int paired = houseSite ? 4 : Math.min(oldParams.size(), newParams.size());
		for (int i = 0; i < paired; i++) {
			if (!convertArgument(values.get(i), oldParams.get(i), newParams.get(i), name, enumParameters, edits, depth)) {
				return false;
			}
		}
		if (houseSite) {
			KmrPascalExpression flag = unwrap(values.get(4));
			if (!(flag instanceof KmrPascalLiteralExpression) || !flag.getText().equalsIgnoreCase("False")) {
				return false; // True adds the house's full wood and stone cost, which the Ex variant needs as numbers
			}
			edits.add(new Edit(values.get(4).getTextRange(), "0, 0"));
			return true;
		}
		if (newParams.size() == oldParams.size()) {
			return true;
		}
		// HouseAddBuildingProgress added one build step; HouseAddBuildingProgressEx adds aBuildSteps of them
		if (name.equalsIgnoreCase("HouseAddBuildingProgress") && newParams.size() == oldParams.size() + 1) {
			edits.add(new Edit(TextRange.from(values.get(values.size() - 1).getTextRange().getEndOffset(), 0), ", 1"));
			return true;
		}
		return false;
	}

	private static boolean convertArgument(@NotNull KmrPascalExpression value, @NotNull KmrPascalParameterIdentifier oldParam, @NotNull KmrPascalParameterIdentifier newParam,
										   @NotNull String name, @NotNull Map<PsiElement, String> enumParameters, @NotNull List<Edit> edits, int depth)
	{
		KmrPascalParameterDeclaration oldGroup = (KmrPascalParameterDeclaration) oldParam.getParent();
		KmrPascalParameterDeclaration newGroup = (KmrPascalParameterDeclaration) newParam.getParent();
		if (!modifierText(oldGroup).equals(modifierText(newGroup))) {
			return false;
		}
		KmrType oldType = KmrTypeProvider.fromTypeSpec(oldGroup.getTypeSpec());
		KmrType newType = KmrTypeProvider.fromTypeSpec(newGroup.getTypeSpec());
		if (oldType.isUnknown() || newType.isUnknown()) {
			return false;
		}
		if (oldType.sameAs(newType)) {
			return true; // aHand: Byte became aHand: Integer and the like: the argument stays
		}
		if (oldType.is(KmrType.Kind.INTEGER) && newType.is(KmrType.Kind.ENUM)) {
			String enumName = enumNameOf(newType);
			return enumName != null && convertValue(value, enumName, MINUS_ONE_IS_ANY.contains(name.toLowerCase(Locale.ROOT)), enumParameters, edits, depth);
		}
		if (oldType.is(KmrType.Kind.SET) && newType.is(KmrType.Kind.SET) && newType.element != null && newType.element.is(KmrType.Kind.ENUM)) {
			String enumName = enumNameOf(newType.element);
			KmrPascalExpression set = unwrap(value);
			if (enumName == null || !(set instanceof KmrPascalArrayExpression)) {
				return false;
			}
			for (KmrPascalExpression element : ((KmrPascalArrayExpression) set).getExpressionList()) {
				if (!convertValue(element, enumName, false, enumParameters, edits, depth)) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	/**
	 * An integer-id value that becomes a member of the given enum: a constant, a reference to a handler parameter
	 * that is being turned into the enum, or a legacy call whose Ex variant returns the enum (rewritten in place).
	 */
	private static boolean convertValue(@NotNull KmrPascalExpression value, @NotNull String enumName, boolean anyAllowed, @NotNull Map<PsiElement, String> enumParameters,
										@NotNull List<Edit> edits, int depth)
	{
		Integer id = constantInt(value, 0);
		if (id != null) {
			String member = KmrPascalLegacyIds.parameterValue(enumName, id, anyAllowed);
			if (member == null || !declares(value, member)) {
				return false;
			}
			edits.add(new Edit(value.getTextRange(), member));
			return true;
		}
		KmrPascalExpression inner = unwrap(value);
		if (inner instanceof KmrPascalIdentifierExpression && !enumParameters.isEmpty()) {
			String parameterEnum = enumParameters.get(KmrPascalInspectionUtil.resolveSingle(inner));
			return parameterEnum != null && parameterEnum.equalsIgnoreCase(enumName);
		}
		if (inner instanceof KmrPascalCallExpression call && call.getExpression() instanceof KmrPascalReferenceElement callee) {
			Plan nested = planCall(callee, enumName, enumParameters, depth + 1);
			if (nested != null) {
				edits.addAll(nested.edits());
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// result
	// ---------------------------------------------------------------------------------------------------------------

	private static boolean convertResult(@NotNull KmrPascalCallExpression call, @NotNull KmrPascalCallable old, @NotNull KmrPascalCallable ex,
										 @Nullable String expectedResultEnum, @NotNull List<Edit> edits)
	{
		if (old.isFunction() != ex.isFunction()) {
			return false;
		}
		if (!old.isFunction()) {
			return expectedResultEnum == null; // two procedures: nothing to convert
		}
		KmrType oldResult = KmrTypeProvider.returnTypeOf(old);
		KmrType newResult = KmrTypeProvider.returnTypeOf(ex);
		if (expectedResultEnum != null) {
			return oldResult.is(KmrType.Kind.INTEGER) && newResult.is(KmrType.Kind.ENUM) && expectedResultEnum.equalsIgnoreCase(enumNameOf(newResult));
		}
		if (oldResult.isUnknown() || newResult.isUnknown()) {
			return false;
		}
		if (oldResult.sameAs(newResult)) {
			return true;
		}
		String enumName = oldResult.is(KmrType.Kind.INTEGER) && newResult.is(KmrType.Kind.ENUM) ? enumNameOf(newResult) : null;
		return enumName != null && convertUseContext(call, enumName, edits);
	}

	/**
	 * Converts the place where an integer-id value (a legacy call's result, a handler parameter) is consumed once the
	 * value becomes an enum: the other side of {@code =} / {@code <>}, the literal set of {@code in}, or the labels of
	 * a {@code case}. Anything else (ordering, arithmetic, assignment, an ordinary call) is not convertible.
	 */
	static boolean convertUseContext(@NotNull KmrPascalExpression value, @NotNull String enumName, @NotNull List<Edit> edits)
	{
		PsiElement context = value;
		while (context.getParent() instanceof KmrPascalParenExpression) {
			context = context.getParent();
		}
		PsiElement parent = context.getParent();
		if (parent instanceof KmrPascalComparisonExpression comparison) {
			IElementType operator = KmrTypeProvider.operatorOf(comparison);
			List<KmrPascalExpression> operands = comparison.getExpressionList();
			if (operands.size() != 2) {
				return false;
			}
			KmrPascalExpression other = operands.get(operands.get(0) == context ? 1 : 0);
			if (operator == KmrPascalTypes.EQ || operator == KmrPascalTypes.NOTEQ) {
				return convertResultConstant(other, enumName, edits);
			}
			if (operator == KmrPascalTypes.IN && operands.get(0) == context && unwrap(other) instanceof KmrPascalArrayExpression set) {
				for (KmrPascalExpression element : set.getExpressionList()) {
					if (!convertResultConstant(element, enumName, edits)) {
						return false;
					}
				}
				return true;
			}
			return false; // ids were ordered, enum members are not comparable the same way
		}
		if (parent instanceof KmrPascalCaseStatement caseStatement && caseStatement.getExpression() == context) {
			for (KmrPascalCaseBranch branch : caseStatement.getCaseBranchList()) {
				for (KmrPascalCaseLabel label : branch.getCaseLabelList()) {
					List<KmrPascalExpression> bounds = label.getExpressionList();
					if (bounds.size() != 1 || !convertResultConstant(bounds.get(0), enumName, edits)) {
						return false; // a range of ids has no enum counterpart
					}
				}
			}
			return true;
		}
		return false;
	}

	private static boolean convertResultConstant(@NotNull KmrPascalExpression constant, @NotNull String enumName, @NotNull List<Edit> edits)
	{
		Integer id = constantInt(constant, 0);
		String member = id == null ? null : KmrPascalLegacyIds.resultValue(enumName, id);
		if (member == null || !declares(constant, member)) {
			return false;
		}
		edits.add(new Edit(constant.getTextRange(), member));
		return true;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------------------------------------------------

	/** The value of a constant integer expression: a number, a signed number, a named constant, in parentheses. */
	@Nullable
	static Integer constantInt(@NotNull KmrPascalExpression expression, int depth)
	{
		KmrPascalExpression e = unwrap(expression);
		if (depth > MAX_CONSTANT_DEPTH) {
			return null;
		}
		if (e instanceof KmrPascalLiteralExpression literal) {
			PsiElement number = literal.getNumber();
			return number == null ? null : parseInt(number.getText());
		}
		if (e instanceof KmrPascalUnaryExpression unary && unary.getExpression() != null) {
			IElementType operator = KmrTypeProvider.operatorOf(unary);
			Integer operand = constantInt(unary.getExpression(), depth + 1);
			if (operand == null) {
				return null;
			}
			return operator == KmrPascalTypes.MINUS ? -operand : operator == KmrPascalTypes.PLUS ? operand : null;
		}
		if (e instanceof KmrPascalIdentifierExpression) {
			PsiElement target = KmrPascalInspectionUtil.resolveSingle(e);
			if (target instanceof KmrPascalConstantDeclaration constant && constant.getExpression() != null) {
				return constantInt(constant.getExpression(), depth + 1);
			}
		}
		return null;
	}

	@Nullable
	private static Integer parseInt(@NotNull String text)
	{
		try {
			return text.startsWith("$") ? Integer.parseInt(text.substring(1), 16) : Integer.parseInt(text);
		} catch (NumberFormatException e) {
			return null; // a real number, or out of range
		}
	}

	@NotNull
	static KmrPascalExpression unwrap(@NotNull KmrPascalExpression expression)
	{
		KmrPascalExpression e = expression;
		while (e instanceof KmrPascalParenExpression paren && paren.getExpression() != null) {
			e = paren.getExpression();
		}
		return e;
	}

	@NotNull
	static String modifierText(@NotNull KmrPascalParameterDeclaration group)
	{
		return group.getArgumentModifier() == null ? "" : group.getArgumentModifier().getText().toLowerCase(Locale.ROOT);
	}

	/** The declared name of an enum type ({@code TKMUnitType}), or null for an anonymous one. */
	@Nullable
	static String enumNameOf(@NotNull KmrType enumType)
	{
		KmrPascalTypeDeclaration declaration = enumType.declaration == null ? null : PsiTreeUtil.getParentOfType(enumType.declaration, KmrPascalTypeDeclaration.class);
		return declaration == null ? null : declaration.getName();
	}

	/** Whether the stubs of the place's game version declare the name (an enum member or type). */
	static boolean declares(@NotNull PsiElement place, @NotNull String name)
	{
		PsiFile file = place.getContainingFile();
		return file != null && !KmrPascalStubScope.getInstance(place.getProject()).findDeclarations(file.getOriginalFile(), name).isEmpty();
	}

	/**
	 * Applies text edits from the end backwards, so the code keeps its own formatting (a PSI replace would reformat
	 * it). Duplicates (the same call reached through two arguments) collapse.
	 */
	static void apply(@NotNull Project project, @NotNull Document document, @NotNull Collection<Edit> edits)
	{
		List<Edit> sorted = new ArrayList<>(new LinkedHashSet<>(edits));
		sorted.sort(Comparator.comparingInt((Edit edit) -> edit.range().getStartOffset()).reversed());
		for (Edit edit : sorted) {
			document.replaceString(edit.range().getStartOffset(), edit.range().getEndOffset(), edit.text());
		}
		PsiDocumentManager.getInstance(project).commitDocument(document);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// quick fix
	// ---------------------------------------------------------------------------------------------------------------

	/** Applies {@link #plan} to the document; the plan is recomputed from the reference at fix time. */
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
			return "Replace deprecated call with its Ex variant";
		}

		@Override
		public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor)
		{
			PsiElement element = descriptor.getPsiElement();
			Plan plan = element instanceof KmrPascalReferenceElement callee ? plan(callee) : null;
			Document document = plan == null ? null : PsiDocumentManager.getInstance(project).getDocument(element.getContainingFile());
			if (document != null) {
				apply(project, document, plan.edits());
			}
		}
	}

}
