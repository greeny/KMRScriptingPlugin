package dev.greeny.kmr.language.flow;

import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalTypeUtil;
import dev.greeny.kmr.language.types.KmrTypeProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Folds expressions made of literals, named constants and enum members into a {@link KmrConst}: {@code MAX_UNITS - 1},
 * {@code not DEBUG}, {@code 'a' + 'b'}, {@code utSerf}. Anything else (variables, calls, reals) is null.
 */
public final class KmrConstEvaluator
{

	private static final int MAX_DEPTH = 16;

	private KmrConstEvaluator()
	{
	}

	@Nullable
	public static KmrConst evaluate(@Nullable KmrPascalExpression expression)
	{
		return evaluate(expression, 0);
	}

	@Nullable
	private static KmrConst evaluate(@Nullable KmrPascalExpression expression, int depth)
	{
		if (expression == null || depth > MAX_DEPTH) {
			return null;
		}
		if (expression instanceof KmrPascalLiteralExpression) {
			return literal((KmrPascalLiteralExpression) expression);
		}
		if (expression instanceof KmrPascalParenExpression) {
			return evaluate(((KmrPascalParenExpression) expression).getExpression(), depth + 1);
		}
		if (expression instanceof KmrPascalIdentifierExpression) {
			PsiElement target = KmrPascalTypeUtil.resolveSingle(expression);
			if (target instanceof KmrPascalConstantDeclaration) {
				return evaluate(((KmrPascalConstantDeclaration) target).getExpression(), depth + 1);
			}
			if (target instanceof KmrPascalEnumValue) {
				String name = ((KmrPascalEnumValue) target).getName();
				return name == null ? null : KmrConst.ofEnum(name);
			}
			return null;
		}
		if (expression instanceof KmrPascalUnaryExpression) {
			KmrConst operand = evaluate(((KmrPascalUnaryExpression) expression).getExpression(), depth + 1);
			IElementType operator = KmrTypeProvider.operatorOf(expression);
			if (operand == null || operator == null) {
				return null;
			}
			if (operator == KmrPascalTypes.NOT) {
				return operand.is(KmrConst.Kind.BOOLEAN) ? operand.negate() : operand.is(KmrConst.Kind.INTEGER) ? KmrConst.ofInt(~operand.number) : null;
			}
			if (operator == KmrPascalTypes.MINUS) {
				return operand.is(KmrConst.Kind.INTEGER) ? KmrConst.ofInt(-operand.number) : null;
			}
			return operand.is(KmrConst.Kind.INTEGER) ? operand : null;
		}
		if (expression instanceof KmrPascalAdditiveExpression || expression instanceof KmrPascalMultiplicativeExpression) {
			List<KmrPascalExpression> operands = expression instanceof KmrPascalAdditiveExpression
				? ((KmrPascalAdditiveExpression) expression).getExpressionList() : ((KmrPascalMultiplicativeExpression) expression).getExpressionList();
			IElementType operator = KmrTypeProvider.operatorOf(expression);
			if (operands.size() != 2 || operator == null) {
				return null;
			}
			KmrConst left = evaluate(operands.get(0), depth + 1);
			KmrConst right = left == null ? null : evaluate(operands.get(1), depth + 1);
			return right == null ? null : binary(operator, left, right);
		}
		if (expression instanceof KmrPascalComparisonExpression) {
			List<KmrPascalExpression> operands = ((KmrPascalComparisonExpression) expression).getExpressionList();
			IElementType operator = KmrTypeProvider.operatorOf(expression);
			if (operands.size() != 2 || operator == null) {
				return null;
			}
			KmrConst left = evaluate(operands.get(0), depth + 1);
			KmrConst right = left == null ? null : evaluate(operands.get(1), depth + 1);
			KmrTruth truth = right == null ? KmrTruth.UNKNOWN : compare(operator, left, right);
			return truth.isKnown() ? KmrConst.of(truth == KmrTruth.TRUE) : null;
		}
		return null;
	}

	@Nullable
	private static KmrConst literal(@NotNull KmrPascalLiteralExpression literal)
	{
		PsiElement first = literal.getFirstChild();
		IElementType type = first == null ? null : first.getNode().getElementType();
		if (type == KmrPascalTypes.TRUE) {
			return KmrConst.TRUE;
		}
		if (type == KmrPascalTypes.FALSE) {
			return KmrConst.FALSE;
		}
		if (type == KmrPascalTypes.NIL) {
			return KmrConst.NIL;
		}
		if (type == KmrPascalTypes.NUMBER) {
			return parseInteger(first.getText());
		}
		if (type == KmrPascalTypes.STRING || type == KmrPascalTypes.CHAR) {
			String decoded = decodeString(literal.getText());
			return decoded == null ? null : KmrConst.ofString(decoded);
		}
		return null;
	}

	/** {@code 42}, {@code $FF}; reals are not constants for the analysis. */
	@Nullable
	public static KmrConst parseInteger(@NotNull String text)
	{
		try {
			if (text.startsWith("$")) {
				return KmrConst.ofInt(Long.parseLong(text.substring(1), 16));
			}
			String lower = text.toLowerCase(Locale.ROOT);
			if (lower.contains(".") || lower.contains("e")) {
				return null;
			}
			return KmrConst.ofInt(Long.parseLong(text));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** The value of a string literal token run: {@code 'it''s'#13#10} becomes {@code it's\r\n}; null when malformed. */
	@Nullable
	public static String decodeString(@NotNull String text)
	{
		StringBuilder sb = new StringBuilder();
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
			} else if (c == '\'') {
				int j = i + 1;
				while (true) {
					if (j >= text.length()) {
						return null;
					}
					if (text.charAt(j) == '\'') {
						if (j + 1 < text.length() && text.charAt(j + 1) == '\'') {
							sb.append('\'');
							j += 2;
							continue;
						}
						break;
					}
					sb.append(text.charAt(j));
					j++;
				}
				i = j + 1;
			} else if (c == '#') {
				int j = i + 1;
				boolean hex = j < text.length() && text.charAt(j) == '$';
				if (hex) {
					j++;
				}
				int start = j;
				while (j < text.length() && (Character.isDigit(text.charAt(j)) || hex && Character.digit(text.charAt(j), 16) >= 0)) {
					j++;
				}
				if (start == j) {
					return null;
				}
				try {
					sb.append((char) Integer.parseInt(text.substring(start, j), hex ? 16 : 10));
				} catch (NumberFormatException e) {
					return null;
				}
				i = j;
			} else {
				return null;
			}
		}
		return sb.toString();
	}

	@Nullable
	private static KmrConst binary(@NotNull IElementType operator, @NotNull KmrConst left, @NotNull KmrConst right)
	{
		boolean ints = left.is(KmrConst.Kind.INTEGER) && right.is(KmrConst.Kind.INTEGER);
		boolean bools = left.is(KmrConst.Kind.BOOLEAN) && right.is(KmrConst.Kind.BOOLEAN);
		if (operator == KmrPascalTypes.PLUS) {
			if (left.is(KmrConst.Kind.STRING) && right.is(KmrConst.Kind.STRING)) {
				return KmrConst.ofString(left.stringValue() + right.stringValue());
			}
			return ints ? KmrConst.ofInt(left.number + right.number) : null;
		}
		if (operator == KmrPascalTypes.MINUS) {
			return ints ? KmrConst.ofInt(left.number - right.number) : null;
		}
		if (operator == KmrPascalTypes.TIMES) {
			return ints ? KmrConst.ofInt(left.number * right.number) : null;
		}
		if (operator == KmrPascalTypes.DIV || operator == KmrPascalTypes.DIVIDE) {
			return ints && right.number != 0 ? KmrConst.ofInt(left.number / right.number) : null;
		}
		if (operator == KmrPascalTypes.MOD) {
			return ints && right.number != 0 ? KmrConst.ofInt(left.number % right.number) : null;
		}
		if (operator == KmrPascalTypes.AND) {
			return bools ? KmrConst.of(left.asBoolean() && right.asBoolean()) : ints ? KmrConst.ofInt(left.number & right.number) : null;
		}
		if (operator == KmrPascalTypes.OR) {
			return bools ? KmrConst.of(left.asBoolean() || right.asBoolean()) : ints ? KmrConst.ofInt(left.number | right.number) : null;
		}
		if (operator == KmrPascalTypes.XOR) {
			return bools ? KmrConst.of(left.asBoolean() != right.asBoolean()) : ints ? KmrConst.ofInt(left.number ^ right.number) : null;
		}
		if (operator == KmrPascalTypes.SHL) {
			return ints ? KmrConst.ofInt(left.number << right.number) : null;
		}
		if (operator == KmrPascalTypes.SHR) {
			return ints ? KmrConst.ofInt(left.number >>> right.number) : null;
		}
		return null;
	}

	/** {@code left op right} for two constants; UNKNOWN when the kinds do not compare (an enum against a number). */
	@NotNull
	public static KmrTruth compare(@NotNull IElementType operator, @NotNull KmrConst left, @NotNull KmrConst right)
	{
		if (operator == KmrPascalTypes.EQ || operator == KmrPascalTypes.NOTEQ) {
			if (left.kind != right.kind) {
				return KmrTruth.UNKNOWN;
			}
			return KmrTruth.of(left.equals(right) == (operator == KmrPascalTypes.EQ));
		}
		Integer order = left.compareTo(right);
		if (order == null) {
			return KmrTruth.UNKNOWN;
		}
		if (operator == KmrPascalTypes.LT) return KmrTruth.of(order < 0);
		if (operator == KmrPascalTypes.LE) return KmrTruth.of(order <= 0);
		if (operator == KmrPascalTypes.GT) return KmrTruth.of(order > 0);
		if (operator == KmrPascalTypes.GE) return KmrTruth.of(order >= 0);
		return KmrTruth.UNKNOWN;
	}

}
