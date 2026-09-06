package dev.greeny.kmr.language.types;

import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.resolve.KmrPascalTypeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Infers {@link KmrType}s: from type elements (declarations) and from expressions (literals, references, calls,
 * operators). Results are cached per PSI element; anything not understood is {@link KmrType#UNKNOWN}.
 * <p>
 * Integers carry their {@link KmrKind} where a type says so (the kind alias types of the stubs, arithmetic with plain
 * integers). Kinds of user declarations are inferred separately by {@link KmrKindInference}, which is not cached per
 * expression: inference reads the expressions assigned to a variable, and those may mention the variable itself.
 */
public final class KmrTypeProvider
{

	private static final Key<CachedValue<KmrType>> EXPRESSION_TYPE = Key.create("KmrPascalExpressionType");
	private static final int MAX_ALIAS_DEPTH = 16;

	private KmrTypeProvider()
	{
	}

	// ---------------------------------------------------------------------------------------------------------------
	// declared types
	// ---------------------------------------------------------------------------------------------------------------

	/** The type described by a {@code type-spec}, following aliases. */
	@NotNull
	public static KmrType fromTypeSpec(@Nullable KmrPascalTypeSpec typeSpec)
	{
		return typeSpec == null ? KmrType.UNKNOWN : fromTypeElement(typeSpec.getTypeElement(), 0);
	}

	@NotNull
	public static KmrType fromTypeElement(@Nullable KmrPascalTypeElement type)
	{
		return fromTypeElement(type, 0);
	}

	@NotNull
	private static KmrType fromTypeElement(@Nullable KmrPascalTypeElement type, int depth)
	{
		if (type == null || depth > MAX_ALIAS_DEPTH) {
			return KmrType.UNKNOWN;
		}
		if (type instanceof KmrPascalTypeReference) {
			String name = ((KmrPascalTypeReference) type).getReferenceName();
			KmrType builtin = builtinType(name);
			if (builtin != null) {
				return builtin;
			}
			PsiElement target = resolve(type);
			if (target instanceof KmrPascalTypeDeclaration) {
				KmrPascalTypeDeclaration declaration = (KmrPascalTypeDeclaration) target;
				KmrType aliased = fromTypeElement(declaration.getTypeSpec().getTypeElement(), depth + 1);
				// TKMUnitID = Integer with "@kind id unit ID": the alias brands the integer
				KmrKind kind = aliased.is(KmrType.Kind.INTEGER) ? KmrKindInference.declaredKindOf(declaration) : null;
				return kind == null ? aliased : aliased.withValueKind(kind);
			}
			return KmrType.UNKNOWN;
		}
		if (type instanceof KmrPascalEnumType) {
			return KmrType.enumOf(type);
		}
		if (type instanceof KmrPascalRecordType) {
			return KmrType.recordOf(type);
		}
		if (type instanceof KmrPascalArrayType) {
			KmrPascalArrayType array = (KmrPascalArrayType) type;
			if (array.getTypeSpec() == null) {
				return KmrType.ARRAY_OF_CONST; // array of const
			}
			// array [0..3, 0..3] of T is indexed as Grid[i, j] or Grid[i][j]: one array level per dimension
			KmrPascalIndexList indexList = array.getIndexList();
			int dimensions = indexList == null ? 1 : Math.max(1, PsiTreeUtil.getChildrenOfTypeAsList(indexList, KmrPascalTypeElement.class).size());
			KmrType result = fromTypeElement(array.getTypeSpec().getTypeElement(), depth + 1);
			for (int i = 0; i < dimensions; i++) {
				result = KmrType.arrayOf(result);
			}
			return result;
		}
		if (type instanceof KmrPascalSetType) {
			return KmrType.setOf(fromTypeElement(PsiTreeUtil.getChildOfType(type, KmrPascalTypeElement.class), depth + 1));
		}
		if (type instanceof KmrPascalRangeType) {
			List<KmrPascalExpression> bounds = ((KmrPascalRangeType) type).getExpressionList();
			return bounds.isEmpty() ? KmrType.INTEGER : typeOf(bounds.get(0));
		}
		if (type instanceof KmrPascalFunctionType || type instanceof KmrPascalProcedureType) {
			return KmrType.proceduralOf(type);
		}
		return KmrType.UNKNOWN;
	}

	/** Built-in type by name, or null for user types. */
	@Nullable
	public static KmrType builtinType(@NotNull String name)
	{
		switch (name.toLowerCase(Locale.ROOT)) {
			case "integer": case "cardinal": case "byte": case "shortint": case "smallint": case "word": case "longint":
			case "longword": case "int64": case "uint64":
				return KmrType.INTEGER;
			case "single": case "double": case "extended": case "real": case "currency":
				return KmrType.REAL;
			case "boolean": case "longbool": case "wordbool": case "bytebool":
				return KmrType.BOOLEAN;
			case "string": case "ansistring": case "widestring": case "unicodestring": case "anystring": case "nativestring": case "tbtstring":
				return KmrType.STRING;
			case "char": case "ansichar": case "widechar":
				return KmrType.CHAR;
			case "variant":
				return KmrType.VARIANT;
			default:
				return null;
		}
	}

	/**
	 * Declared type of a resolved element (variable, parameter, field, constant, enum value, function). Kinds appear
	 * only when the declared type names a kind alias; see {@link KmrKindInference#kindOfDeclaration} for inferred ones.
	 */
	@NotNull
	public static KmrType declaredTypeOf(@Nullable PsiElement target)
	{
		if (target instanceof KmrPascalTypedIdentifier) {
			return fromTypeSpec(((KmrPascalTypedIdentifier) target).getType());
		}
		if (target instanceof KmrPascalConstantDeclaration) {
			KmrPascalExpression value = ((KmrPascalConstantDeclaration) target).getExpression();
			return value == null ? KmrType.UNKNOWN : typeOf(value);
		}
		if (target instanceof KmrPascalEnumValue) {
			KmrPascalEnumType enumType = PsiTreeUtil.getParentOfType(target, KmrPascalEnumType.class);
			return enumType == null ? KmrType.UNKNOWN : KmrType.enumOf(enumType);
		}
		if (target instanceof KmrPascalFunctionDeclaration) {
			return fromTypeSpec(((KmrPascalFunctionDeclaration) target).getTypeSpec());
		}
		if (target instanceof KmrPascalProcedureDeclaration) {
			return KmrType.proceduralOf(target);
		}
		return KmrType.UNKNOWN;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// expressions
	// ---------------------------------------------------------------------------------------------------------------

	@NotNull
	public static KmrType typeOf(@NotNull KmrPascalExpression expression)
	{
		KmrType cached = CachedValuesManager.getCachedValue(expression, EXPRESSION_TYPE, () -> {
			KmrType type = RecursionManager.doPreventingRecursion(expression, true, () -> compute(expression));
			return CachedValueProvider.Result.create(type == null ? KmrType.UNKNOWN : type, PsiModificationTracker.MODIFICATION_COUNT);
		});
		return cached == null ? KmrType.UNKNOWN : cached;
	}

	@NotNull
	private static KmrType compute(@NotNull KmrPascalExpression expression)
	{
		if (expression instanceof KmrPascalLiteralExpression) {
			return literalType((KmrPascalLiteralExpression) expression);
		}
		if (expression instanceof KmrPascalParenExpression) {
			KmrPascalExpression inner = ((KmrPascalParenExpression) expression).getExpression();
			return inner == null ? KmrType.UNKNOWN : typeOf(inner);
		}
		if (expression instanceof KmrPascalArrayExpression) {
			List<KmrType> elements = new ArrayList<>();
			for (KmrPascalExpression element : ((KmrPascalArrayExpression) expression).getExpressionList()) {
				elements.add(typeOf(element));
			}
			return KmrType.literalList(elements);
		}
		if (expression instanceof KmrPascalReferenceExpression) {
			return referenceType((KmrPascalReferenceExpression) expression);
		}
		if (expression instanceof KmrPascalCallExpression) {
			return callType((KmrPascalCallExpression) expression);
		}
		if (expression instanceof KmrPascalIndexExpression) {
			return indexType((KmrPascalIndexExpression) expression);
		}
		if (expression instanceof KmrPascalAddressExpression) {
			return KmrType.proceduralOf(null);
		}
		if (expression instanceof KmrPascalUnaryExpression) {
			return unaryType((KmrPascalUnaryExpression) expression);
		}
		if (expression instanceof KmrPascalComparisonExpression) {
			return KmrType.BOOLEAN;
		}
		if (expression instanceof KmrPascalAdditiveExpression || expression instanceof KmrPascalMultiplicativeExpression) {
			List<KmrPascalExpression> operands = expression instanceof KmrPascalAdditiveExpression
				? ((KmrPascalAdditiveExpression) expression).getExpressionList() : ((KmrPascalMultiplicativeExpression) expression).getExpressionList();
			IElementType operator = operatorOf(expression);
			if (operands.size() != 2 || operator == null) {
				return KmrType.UNKNOWN;
			}
			return binaryType(operator, typeOf(operands.get(0)), typeOf(operands.get(1)));
		}
		return KmrType.UNKNOWN;
	}

	@NotNull
	private static KmrType literalType(@NotNull KmrPascalLiteralExpression literal)
	{
		PsiElement first = literal.getFirstChild();
		IElementType type = first == null ? null : first.getNode().getElementType();
		if (type == KmrPascalTypes.NUMBER) {
			String text = first.getText();
			return text.startsWith("$") || !(text.contains(".") || text.toLowerCase(Locale.ROOT).contains("e")) ? KmrType.INTEGER : KmrType.REAL;
		}
		if (type == KmrPascalTypes.STRING || type == KmrPascalTypes.CHAR) {
			return literal.getChildren().length == 0 && isSingleChar(literal.getText()) ? KmrType.CHAR : KmrType.STRING;
		}
		if (type == KmrPascalTypes.TRUE || type == KmrPascalTypes.FALSE) {
			return KmrType.BOOLEAN;
		}
		if (type == KmrPascalTypes.NIL) {
			return KmrType.NIL;
		}
		return KmrType.UNKNOWN;
	}

	/** {@code 'a'} or {@code #13} denotes a single character. */
	private static boolean isSingleChar(@NotNull String text)
	{
		if (text.startsWith("#")) {
			return !text.contains("'") && text.indexOf('#', 1) < 0;
		}
		return text.length() == 3 && text.startsWith("'") && text.endsWith("'") || text.equals("''''");
	}

	@NotNull
	private static KmrType referenceType(@NotNull KmrPascalReferenceExpression reference)
	{
		PsiElement target = resolve(reference);
		if (target == null) {
			return KmrType.UNKNOWN;
		}
		if (target instanceof KmrPascalRoutineDeclaration || KmrPascalCallable.of(target) != null) {
			// a routine name used as a value: a parameterless function call yields its result
			KmrPascalCallable callable = KmrPascalCallable.of(target);
			if (callable != null && callable.isFunction() && callable.getParameterCount() == 0 && !isCallee(reference)) {
				return returnTypeOf(callable);
			}
			if (callable != null && target instanceof KmrPascalFunctionDeclaration && PsiTreeUtil.isAncestor(target, reference, true)) {
				return returnTypeOf(callable); // Result / function name inside its own body
			}
			return KmrType.proceduralOf(target);
		}
		return declaredTypeOf(target);
	}

	private static boolean isCallee(@NotNull KmrPascalExpression expression)
	{
		PsiElement parent = expression.getParent();
		return parent instanceof KmrPascalCallExpression && ((KmrPascalCallExpression) parent).getExpression() == expression;
	}

	@NotNull
	private static KmrType callType(@NotNull KmrPascalCallExpression call)
	{
		KmrPascalExpression callee = call.getExpression();
		if (callee instanceof KmrPascalIdentifierExpression) {
			String name = ((KmrPascalIdentifierExpression) callee).getReferenceName();
			PsiElement target = resolve(callee);
			if (target instanceof KmrPascalTypeDeclaration) {
				return fromTypeSpec(((KmrPascalTypeDeclaration) target).getTypeSpec()); // TKMUnitType(1): a cast
			}
			if (target == null) {
				KmrType cast = builtinType(name);
				return cast != null ? cast : KmrType.UNKNOWN; // Integer(x); standard routines resolve into System.script
			}
		}
		KmrPascalCallable callable = KmrPascalCallable.of(resolve(callee));
		if (callable == null) {
			return KmrType.UNKNOWN;
		}
		return callable.isFunction() ? returnTypeOf(callable) : KmrType.VOID;
	}

	/** Result type of a function or procedural-type field. */
	@NotNull
	public static KmrType returnTypeOf(@NotNull KmrPascalCallable callable)
	{
		return fromTypeSpec(callable.getReturnType());
	}

	@NotNull
	private static KmrType indexType(@NotNull KmrPascalIndexExpression index)
	{
		List<KmrPascalExpression> children = index.getExpressionList();
		if (children.isEmpty()) {
			return KmrType.UNKNOWN;
		}
		KmrType base = typeOf(children.get(0));
		int indexes = children.size() - 1;
		for (int i = 0; i < indexes; i++) {
			if (base.is(KmrType.Kind.ARRAY) && base.element != null) {
				base = base.element;
			} else if (base.is(KmrType.Kind.STRING)) {
				base = KmrType.CHAR;
			} else {
				return KmrType.UNKNOWN;
			}
		}
		return base;
	}

	@NotNull
	private static KmrType unaryType(@NotNull KmrPascalUnaryExpression unary)
	{
		KmrPascalExpression operand = unary.getExpression();
		if (operand == null) {
			return KmrType.UNKNOWN;
		}
		KmrType type = typeOf(operand);
		IElementType operator = operatorOf(unary);
		if (operator == KmrPascalTypes.NOT) {
			return type.is(KmrType.Kind.BOOLEAN) || type.is(KmrType.Kind.INTEGER) ? type.withValueKind(null) : KmrType.UNKNOWN;
		}
		return type.isNumeric() ? type.withValueKind(null) : KmrType.UNKNOWN;
	}

	/**
	 * Result type of a binary arithmetic/logical operator, or UNKNOWN when the operands do not fit the operator
	 * (the inspection reports that when both operand types are known).
	 */
	@NotNull
	public static KmrType binaryType(@NotNull IElementType operator, @NotNull KmrType left, @NotNull KmrType right)
	{
		KmrType result = binaryTypeWithoutKinds(operator, left, right);
		return result.is(KmrType.Kind.INTEGER) ? result.withValueKind(arithmeticKind(operator, left, right)) : result;
	}

	/**
	 * The kind of an integer arithmetic result: a branded operand combined with a plain integer keeps its kind when
	 * its policy allows arithmetic ({@code X + 1} is still an X coordinate); two operands of the same kind stay
	 * branded only for counts ({@code Ticks + Ticks}); anything else ({@code X1 - X2}, {@code Hand + Hand}) is plain.
	 */
	@Nullable
	static KmrKind arithmeticKind(@NotNull IElementType operator, @NotNull KmrType left, @NotNull KmrType right)
	{
		return arithmeticKind(operator, left.valueKind, right.valueKind);
	}

	@Nullable
	public static KmrKind arithmeticKind(@NotNull IElementType operator, @Nullable KmrKind l, @Nullable KmrKind r)
	{
		if (operator == KmrPascalTypes.AND || operator == KmrPascalTypes.OR || operator == KmrPascalTypes.XOR
			|| operator == KmrPascalTypes.SHL || operator == KmrPascalTypes.SHR) {
			return null; // bit operations yield plain integers
		}
		if (l != null && r != null) {
			return l.sameAs(r) && l.policy == KmrKind.Policy.COUNT ? l : null;
		}
		KmrKind kind = l != null ? l : r;
		return kind != null && kind.policy.allowsArithmetic ? kind : null;
	}

	@NotNull
	private static KmrType binaryTypeWithoutKinds(@NotNull IElementType operator, @NotNull KmrType left, @NotNull KmrType right)
	{
		if (left.isUnknown() || right.isUnknown() || left.is(KmrType.Kind.VARIANT) || right.is(KmrType.Kind.VARIANT)) {
			return KmrType.UNKNOWN;
		}
		boolean bothNumeric = left.isNumeric() && right.isNumeric();
		boolean bothBoolean = left.is(KmrType.Kind.BOOLEAN) && right.is(KmrType.Kind.BOOLEAN);
		boolean bothInteger = left.is(KmrType.Kind.INTEGER) && right.is(KmrType.Kind.INTEGER);
		boolean stringish = isStringish(left) && isStringish(right);
		boolean sets = isSetish(left) && isSetish(right);

		if (operator == KmrPascalTypes.PLUS) {
			if (stringish) return KmrType.STRING;
			if (sets) return setResult(left, right);
			if (bothNumeric) return bothInteger ? KmrType.INTEGER : KmrType.REAL;
			return KmrType.UNKNOWN;
		}
		if (operator == KmrPascalTypes.MINUS || operator == KmrPascalTypes.TIMES) {
			if (sets) return setResult(left, right);
			if (bothNumeric) return bothInteger ? KmrType.INTEGER : KmrType.REAL;
			// the KaM fork allows "WT := wtFish - 3": enum minus integer stays the enum
			if (operator == KmrPascalTypes.MINUS && left.is(KmrType.Kind.ENUM) && right.is(KmrType.Kind.INTEGER)) return left;
			if (operator == KmrPascalTypes.MINUS && right.is(KmrType.Kind.ENUM) && left.is(KmrType.Kind.INTEGER)) return right;
			return KmrType.UNKNOWN;
		}
		if (operator == KmrPascalTypes.DIVIDE) {
			// the game's PascalScript is built without PS_DELPHIDIV: "/" on two integers is an integer division
			if (bothNumeric) return bothInteger ? KmrType.INTEGER : KmrType.REAL;
			return KmrType.UNKNOWN;
		}
		if (operator == KmrPascalTypes.DIV || operator == KmrPascalTypes.MOD || operator == KmrPascalTypes.SHL || operator == KmrPascalTypes.SHR) {
			return bothInteger ? KmrType.INTEGER : KmrType.UNKNOWN;
		}
		if (operator == KmrPascalTypes.AND || operator == KmrPascalTypes.OR || operator == KmrPascalTypes.XOR) {
			if (bothBoolean) return KmrType.BOOLEAN;
			if (bothInteger) return KmrType.INTEGER;
			return KmrType.UNKNOWN;
		}
		return KmrType.UNKNOWN;
	}

	/** Whether the operand types fit the operator; false only when both are known and do not fit. */
	public static boolean binaryOperandsFit(@NotNull IElementType operator, @NotNull KmrType left, @NotNull KmrType right)
	{
		if (left.isUnknown() || right.isUnknown() || left.is(KmrType.Kind.VARIANT) || right.is(KmrType.Kind.VARIANT)) {
			return true;
		}
		if (operator == KmrPascalTypes.IN) {
			return left.isOrdinal() && (isSetish(right));
		}
		if (operator == KmrPascalTypes.EQ || operator == KmrPascalTypes.NOTEQ) {
			return KmrType.isAssignable(left, right) || KmrType.isAssignable(right, left) || left.is(KmrType.Kind.NIL) || right.is(KmrType.Kind.NIL);
		}
		if (operator == KmrPascalTypes.LT || operator == KmrPascalTypes.GT || operator == KmrPascalTypes.LE || operator == KmrPascalTypes.GE) {
			return left.isNumeric() && right.isNumeric() || isStringish(left) && isStringish(right)
				|| left.is(KmrType.Kind.ENUM) && left.sameAs(right) || left.is(KmrType.Kind.BOOLEAN) && right.is(KmrType.Kind.BOOLEAN)
				|| isSetish(left) && isSetish(right);
		}
		return !binaryType(operator, left, right).isUnknown();
	}

	private static boolean isStringish(@NotNull KmrType type)
	{
		return type.is(KmrType.Kind.STRING) || type.is(KmrType.Kind.CHAR);
	}

	private static boolean isSetish(@NotNull KmrType type)
	{
		return type.is(KmrType.Kind.SET) || type.is(KmrType.Kind.LITERAL_LIST);
	}

	@NotNull
	private static KmrType setResult(@NotNull KmrType left, @NotNull KmrType right)
	{
		return left.is(KmrType.Kind.SET) ? left : right.is(KmrType.Kind.SET) ? right : left;
	}

	/** The operator token of a unary/binary expression node. */
	@Nullable
	public static IElementType operatorOf(@NotNull KmrPascalExpression expression)
	{
		for (PsiElement child = expression.getFirstChild(); child != null; child = child.getNextSibling()) {
			IElementType type = child.getNode().getElementType();
			if (!(child instanceof KmrPascalExpression) && KmrPascalTokenSets.OPERATOR_TOKENS.contains(type)) {
				return type;
			}
		}
		return null;
	}

	@Nullable
	private static PsiElement resolve(@NotNull PsiElement element)
	{
		return KmrPascalTypeUtil.resolveSingle(element);
	}

}
