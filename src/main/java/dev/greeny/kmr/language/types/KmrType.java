package dev.greeny.kmr.language.types;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.KmrPascalTypeDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A (deliberately coarse) PascalScript type. All integer types are one kind, all real types are one kind, all string
 * types are one kind: the game is lenient there and so are we. Enums and records are identified by their declaring
 * PSI element. {@link #UNKNOWN} means "no idea", and every check treats it as compatible with everything.
 * <p>
 * An INTEGER may additionally carry a {@link #valueKind} (unit ID, hand index, ...; see {@link KmrKind}). Kinds are
 * ignored by {@link #sameAs}/{@link #isAssignable}: they are checked separately by the kind inspection.
 */
public final class KmrType
{

	public enum Kind
	{
		UNKNOWN, INTEGER, REAL, BOOLEAN, STRING, CHAR, VARIANT, NIL,
		/** result of calling a procedure */
		VOID,
		ENUM, RECORD, ARRAY, SET, PROCEDURAL,
		/** {@code [a, b, c]}: a set or array literal, typed by context */
		LITERAL_LIST,
		/** {@code array of const} parameter: accepts a literal list of anything */
		ARRAY_OF_CONST
	}

	public static final KmrType UNKNOWN = new KmrType(Kind.UNKNOWN, null, null, Collections.emptyList(), null);
	public static final KmrType INTEGER = new KmrType(Kind.INTEGER, null, null, Collections.emptyList(), null);
	public static final KmrType REAL = new KmrType(Kind.REAL, null, null, Collections.emptyList(), null);
	public static final KmrType BOOLEAN = new KmrType(Kind.BOOLEAN, null, null, Collections.emptyList(), null);
	public static final KmrType STRING = new KmrType(Kind.STRING, null, null, Collections.emptyList(), null);
	public static final KmrType CHAR = new KmrType(Kind.CHAR, null, null, Collections.emptyList(), null);
	public static final KmrType VARIANT = new KmrType(Kind.VARIANT, null, null, Collections.emptyList(), null);
	public static final KmrType NIL = new KmrType(Kind.NIL, null, null, Collections.emptyList(), null);
	public static final KmrType VOID = new KmrType(Kind.VOID, null, null, Collections.emptyList(), null);
	public static final KmrType ARRAY_OF_CONST = new KmrType(Kind.ARRAY_OF_CONST, null, null, Collections.emptyList(), null);

	public final Kind kind;
	/** Declaring element for ENUM (the enum-type node), RECORD (the record-type node) and PROCEDURAL (the type node). */
	@Nullable
	public final PsiElement declaration;
	/** Element type for ARRAY and SET. */
	@Nullable
	public final KmrType element;
	/** Element types of a LITERAL_LIST, in order. */
	public final List<KmrType> elements;
	/** What an INTEGER means for the game (unit ID, hand index, ...); null when unknown or not an integer. */
	@Nullable
	public final KmrKind valueKind;

	private KmrType(@NotNull Kind kind, @Nullable PsiElement declaration, @Nullable KmrType element, @NotNull List<KmrType> elements, @Nullable KmrKind valueKind)
	{
		this.kind = kind;
		this.declaration = declaration;
		this.element = element;
		this.elements = elements;
		this.valueKind = valueKind;
	}

	/** This integer type carrying the given kind (null removes the kind); non-integers are returned unchanged. */
	@NotNull
	public KmrType withValueKind(@Nullable KmrKind valueKind)
	{
		if (kind != Kind.INTEGER || valueKind == this.valueKind) {
			return this;
		}
		return new KmrType(kind, declaration, element, elements, valueKind);
	}

	/** Whether this is an integer carrying a kind. */
	public boolean hasValueKind()
	{
		return valueKind != null;
	}

	public static KmrType enumOf(@NotNull PsiElement enumType)
	{
		return new KmrType(Kind.ENUM, enumType, null, Collections.emptyList(), null);
	}

	public static KmrType recordOf(@NotNull PsiElement recordType)
	{
		return new KmrType(Kind.RECORD, recordType, null, Collections.emptyList(), null);
	}

	public static KmrType proceduralOf(@Nullable PsiElement proceduralType)
	{
		return new KmrType(Kind.PROCEDURAL, proceduralType, null, Collections.emptyList(), null);
	}

	public static KmrType arrayOf(@NotNull KmrType element)
	{
		return new KmrType(Kind.ARRAY, null, element, Collections.emptyList(), null);
	}

	public static KmrType setOf(@NotNull KmrType element)
	{
		return new KmrType(Kind.SET, null, element, Collections.emptyList(), null);
	}

	public static KmrType literalList(@NotNull List<KmrType> elements)
	{
		return new KmrType(Kind.LITERAL_LIST, null, null, elements, null);
	}

	public boolean isUnknown()
	{
		return kind == Kind.UNKNOWN;
	}

	public boolean is(@NotNull Kind kind)
	{
		return this.kind == kind;
	}

	public boolean isNumeric()
	{
		return kind == Kind.INTEGER || kind == Kind.REAL;
	}

	/** Integer, enum, char, boolean: usable as for-loop variable, case selector, set element, array index. */
	public boolean isOrdinal()
	{
		return kind == Kind.INTEGER || kind == Kind.ENUM || kind == Kind.CHAR || kind == Kind.BOOLEAN;
	}

	/** Same kind and, for enums/records/procedural types, the same declaration. */
	public boolean sameAs(@NotNull KmrType other)
	{
		if (kind != other.kind) {
			return false;
		}
		switch (kind) {
			case ENUM:
			case RECORD:
				return declaration != null && other.declaration != null
					&& (declaration == other.declaration || declaration.getManager().areElementsEquivalent(declaration, other.declaration));
			case ARRAY:
			case SET:
				return element != null && other.element != null && (element.isUnknown() || other.element.isUnknown() || element.sameAs(other.element));
			default:
				return true;
		}
	}

	/**
	 * Whether a value of type {@code source} can be assigned to / passed as {@code target}. Unknown on either side
	 * is always fine; the rules otherwise follow PascalScript: integer to real is implicit, real to integer is not,
	 * enums are distinct from integers and from each other, chars widen to strings, nil fits procedural types.
	 */
	public static boolean isAssignable(@NotNull KmrType target, @NotNull KmrType source)
	{
		if (target.isUnknown() || source.isUnknown() || target.is(Kind.VARIANT) || source.is(Kind.VARIANT)) {
			return true;
		}
		if (source.is(Kind.VOID)) {
			return false;
		}
		switch (target.kind) {
			case INTEGER:
				return source.is(Kind.INTEGER);
			case REAL:
				return source.isNumeric();
			case BOOLEAN:
				return source.is(Kind.BOOLEAN);
			case STRING:
				return source.is(Kind.STRING) || source.is(Kind.CHAR);
			case CHAR:
				return source.is(Kind.CHAR) || source.is(Kind.STRING);
			case ENUM:
			case RECORD:
				return source.sameAs(target);
			case ARRAY:
				if (source.is(Kind.LITERAL_LIST)) {
					return target.element == null || source.elements.stream().allMatch(e -> isAssignable(target.element, e));
				}
				return source.is(Kind.ARRAY) && (target.element == null || source.element == null || isAssignable(target.element, source.element));
			case SET:
				if (source.is(Kind.LITERAL_LIST)) {
					return target.element == null || source.elements.stream().allMatch(e -> isAssignable(target.element, e));
				}
				return source.is(Kind.SET) && (target.element == null || source.element == null || isAssignable(target.element, source.element));
			case PROCEDURAL:
				return source.is(Kind.PROCEDURAL) || source.is(Kind.NIL);
			case ARRAY_OF_CONST:
				return source.is(Kind.LITERAL_LIST) || source.is(Kind.ARRAY);
			case NIL:
			case LITERAL_LIST:
			case VOID:
			default:
				return true;
		}
	}

	/**
	 * {@link #isAssignable} plus what the KaM Remake fork of PascalScript additionally accepts in an assignment
	 * ({@code CheckCompatType(..., aEnumAsInt = True)}): enums and integers both ways. Arguments stay strict.
	 */
	public static boolean isAssignableInAssignment(@NotNull KmrType target, @NotNull KmrType source)
	{
		if (isAssignable(target, source)) {
			return true;
		}
		return target.is(Kind.INTEGER) && source.is(Kind.ENUM) || target.is(Kind.ENUM) && source.is(Kind.INTEGER);
	}

	/** Display name with the kind in words: "Integer (unit ID)", "array of Integer (unit ID)". */
	@NotNull
	public String describe()
	{
		if (valueKind != null) {
			return this + " (" + valueKind.label + ")";
		}
		if ((kind == Kind.ARRAY || kind == Kind.SET) && element != null) {
			return (kind == Kind.ARRAY ? "array of " : "set of ") + element.describe();
		}
		return toString();
	}

	/** Display name for messages (kinds are not shown, see {@link #describe()}). */
	@Override
	public String toString()
	{
		switch (kind) {
			case UNKNOWN: return "unknown";
			case INTEGER: return "Integer";
			case REAL: return "Single";
			case BOOLEAN: return "Boolean";
			case STRING: return "String";
			case CHAR: return "Char";
			case VARIANT: return "Variant";
			case NIL: return "nil";
			case VOID: return "no value (procedure call)";
			case ENUM:
			case RECORD: return declarationName();
			case ARRAY: return "array of " + element;
			case SET: return "set of " + element;
			case PROCEDURAL: return "procedural type";
			case LITERAL_LIST: return "[...]";
			case ARRAY_OF_CONST: return "array of const";
			default: return kind.name().toLowerCase();
		}
	}

	@NotNull
	private String declarationName()
	{
		if (declaration == null) {
			return kind == Kind.ENUM ? "enum" : "record";
		}
		KmrPascalTypeDeclaration typeDeclaration = declaration instanceof KmrPascalTypeDeclaration
			? (KmrPascalTypeDeclaration) declaration : PsiTreeUtil.getParentOfType(declaration, KmrPascalTypeDeclaration.class);
		String name = typeDeclaration == null ? null : typeDeclaration.getName();
		if (name == null && declaration instanceof PsiNamedElement) {
			name = ((PsiNamedElement) declaration).getName();
		}
		return name != null ? name : (kind == Kind.ENUM ? "enum" : "record");
	}

	@Override
	public boolean equals(Object o)
	{
		return o instanceof KmrType && sameAs((KmrType) o) && ((KmrType) o).sameAs(this);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(kind);
	}

}
