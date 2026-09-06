package dev.greeny.kmr.language.types;

import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.tree.IElementType;
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kinds of user declarations (variables, record fields, parameters, function results and the elements of arrays
 * they hold), in priority order:
 * <ol>
 *   <li>an explicit {@code @kind unitId} (alias name, kind label, or a free label; {@code none} disables inference)
 *       above the declaration, or {@code @kind aParam unitId} above the routine (or the API stub's procedural-type
 *       field) for a parameter, or {@code @kind X xCoordinate} above a {@code X, Y: Integer} group for one name;
 *       this is also how the API stubs declare their kinds;</li>
 *   <li>the parameter of a recognised event handler takes the kind of the event stub's parameter at that position;</li>
 *   <li>flow inference over the compilation unit: the kinds of everything assigned to the slot ({@code X := e},
 *       {@code for X := a to b}, {@code X} passed to a {@code var}/{@code out} parameter, {@code Result := e},
 *       arguments passed for a parameter). One consistent kind wins; none stays unknown; several is a
 *       {@link Result#conflict} and the slot is treated as unknown.</li>
 * </ol>
 * A slot is a declaration plus an index depth and, for indexed places, the static index keys: {@code (Units, 1)} is
 * "an element of Units", {@code (Units, 1, [3])} is {@code Units[3]}, and {@code Units[UNIT_A]} with a constant index
 * is its own slot as well. A dynamic index ({@code Units[I]}) matches every slot loosely: its writes count for every
 * element, and reads through it take the common kind of all writes but are never reported as conflicting.
 * <p>
 * The static keys travel with a value that is passed around whole: after {@code Rows[I] := Attack} or
 * {@code Handle(Attack)} the slot {@code Rows[I][ATTACKER]} and the parameter's {@code aAttack[ATTACKER]} read the kind
 * written to {@code Attack[ATTACKER]}, not the common kind of all of Attack's elements. An array holding elements of
 * several kinds is "mixed" through a computed index; a mixed value stays unknown wherever it flows (a computed index
 * into it is assumed to pick the right element) and it is never reported.
 * <p>
 * An expression can be annotated: {@code {@kind houseId}} above an assignment names the kind of the assigned value,
 * and the same comment directly in front of any expression (an argument, an operand) names that expression's kind;
 * {@code {@kind none}} makes it unknown.
 * <p>
 * Kinds of expressions ({@link #kindOf(KmrPascalExpression)}) are evaluated on demand and never cached per
 * expression: the value assigned to a variable may mention the variable ({@code Counter := Counter + 1}), so the
 * evaluation is cyclic by nature. Slot results are cached per declaration; a slot re-entered while it is being
 * computed counts as unknown, and results that depended on such an incomplete slot are not cached.
 */
public final class KmrKindInference
{

	private static final Key<CachedValue<Map<String, Result>>> RESULTS = Key.create("KmrPascalKindResults");
	private static final Key<CachedValue<Sites>> SITES = Key.create("KmrPascalKindSites");
	private static final Set<String> NO_KIND_LABELS = Set.of("none", "any", "-");
	/**
	 * Internal marker for a value of several undecidable kinds (a computed index into an array whose elements differ).
	 * It flows through arrays and parameters so that the one element whose kind happens to be known cannot speak for
	 * the whole array, and it is turned into "unknown" before any result leaves this class.
	 */
	private static final KmrKind MIXED = KmrKind.label("<mixed>");
	/** Slots being computed on this thread, innermost last; see {@link Frame}. */
	private static final ThreadLocal<List<Frame>> STACK = ThreadLocal.withInitial(ArrayList::new);

	private static final class Frame
	{
		final Slot slot;
		/** Set when a slot below this frame was re-entered: this result saw an incomplete answer and must not be cached. */
		boolean incomplete;

		Frame(@NotNull Slot slot)
		{
			this.slot = slot;
		}
	}

	private KmrKindInference()
	{
	}

	/** One kinded value flowing into a slot: the expression (or argument) and the kind it carries. */
	public static final class Source
	{
		public final PsiElement element;
		public final KmrKind kind;

		Source(@NotNull PsiElement element, @NotNull KmrKind kind)
		{
			this.element = element;
			this.kind = kind;
		}
	}

	/** Outcome for one slot. */
	public static final class Result
	{
		public static final Result UNKNOWN = new Result(null, false, Collections.emptyList(), false);
		/** Several kinds through computed indexes: unknown, not reported, and mixed wherever the value flows. */
		static final Result MIXED_RESULT = new Result(null, false, Collections.emptyList(), true);

		/** The kind; null when unknown, mixed or conflicting. */
		@Nullable
		public final KmrKind kind;
		/** Several different kinds flow into the slot through static indexes; {@link #sources} lists them. */
		public final boolean conflict;
		public final List<Source> sources;
		private final boolean mixed;

		Result(@Nullable KmrKind kind, boolean conflict, @NotNull List<Source> sources, boolean mixed)
		{
			this.kind = kind;
			this.conflict = conflict;
			this.sources = sources;
			this.mixed = mixed;
		}

		static Result single(@NotNull KmrKind kind)
		{
			return new Result(kind, false, Collections.emptyList(), false);
		}

		/** The kind for further inference: the kind, {@link #MIXED} for mixed and conflicting slots, null when unknown. */
		@Nullable
		KmrKind valueKind()
		{
			return kind != null ? kind : conflict || mixed ? MIXED : null;
		}

		/** Distinct kinds of the conflicting sources, in order of appearance. */
		@NotNull
		public List<KmrKind> kinds()
		{
			List<KmrKind> result = new ArrayList<>();
			for (Source source : sources) {
				if (!result.contains(source.kind)) {
					result.add(source.kind);
				}
			}
			return result;
		}
	}

	/** An assignable place: a declaration, how many index levels deep, and the static index at each level (null = dynamic). */
	public static final class Slot
	{
		public final PsiElement declaration;
		public final int depth;
		/** One entry per index level: a normalized constant ("3", "const:unit_a", "enum:utserf") or null for a computed index. */
		public final List<String> keys;

		Slot(@NotNull PsiElement declaration, int depth, @NotNull List<String> keys)
		{
			this.declaration = declaration;
			this.depth = depth;
			this.keys = keys;
		}

		/** A slot addressing the declaration's value {@code depth} levels deep through unknown indexes. */
		static Slot dynamic(@NotNull PsiElement declaration, int depth)
		{
			return new Slot(declaration, depth, Collections.nCopies(depth, null));
		}

		/** True when every index on the way is a constant. */
		public boolean isStatic()
		{
			return keys.stream().noneMatch(Objects::isNull);
		}

		/** This slot the given index path further down (one key per level, null for a computed index). */
		Slot deeper(@NotNull List<String> path)
		{
			if (path.isEmpty()) {
				return this;
			}
			List<String> extended = new ArrayList<>(keys);
			extended.addAll(path);
			return new Slot(declaration, depth + path.size(), extended);
		}

		String cacheKey()
		{
			StringBuilder sb = new StringBuilder().append(depth);
			for (String key : keys) {
				sb.append(':').append(key == null ? "*" : key);
			}
			return sb.toString();
		}

		boolean sameAs(@NotNull Slot other)
		{
			return depth == other.depth && keys.equals(other.keys) && equivalent(declaration, other.declaration);
		}

		@NotNull
		public String name()
		{
			return declaration instanceof KmrPascalNamedElement ? String.valueOf(((KmrPascalNamedElement) declaration).getName()) : "?";
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// public API
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * The kind of an expression's value: kinds the API declares through its alias types, plus the inferred kinds of
	 * user declarations; null when unknown (or conflicting).
	 */
	@Nullable
	public static KmrKind kindOf(@NotNull KmrPascalExpression expression)
	{
		return kindOf(expression, 0);
	}

	/** The kind {@code levels} array levels inside the expression's value ({@code kindOf(Units, 1)} = an element of Units). */
	@Nullable
	public static KmrKind kindOf(@NotNull KmrPascalExpression expression, int levels)
	{
		return known(kindOf(expression, dynamicPath(levels)));
	}

	/** A mixed kind is "unknown" outside this class. */
	@Nullable
	private static KmrKind known(@Nullable KmrKind kind)
	{
		return kind == MIXED ? null : kind;
	}

	/** An index path of {@code levels} computed indexes. */
	@NotNull
	private static List<String> dynamicPath(int levels)
	{
		return Collections.nCopies(levels, null);
	}

	/**
	 * The kind at the given index path inside the expression's value ({@code kindOf(Rows, [null, "3"])} = element 3
	 * of any row of Rows); {@link #MIXED} when the value is known to hold several kinds there.
	 */
	@Nullable
	private static KmrKind kindOf(@NotNull KmrPascalExpression expression, @NotNull List<String> path)
	{
		Explicit annotation = annotationOf(expression);
		if (annotation != null && annotation.depth == path.size()) {
			return annotation.kind;
		}
		KmrPascalExpression e = expression;
		while (e instanceof KmrPascalParenExpression) {
			e = ((KmrPascalParenExpression) e).getExpression();
			if (e == null) {
				return null;
			}
		}
		if (e instanceof KmrPascalReferenceExpression) {
			PsiElement target = KmrPascalTypeUtil.resolveSingle(e);
			if (target != null && isInferable(target)) {
				if (!isCallable(target)) {
					return declarationKind(target, path);
				}
				// a parameterless function used as a value (States.GameTime, Result inside a function) yields its result
				KmrPascalCallable callable = KmrPascalCallable.of(target);
				boolean callee = e.getParent() instanceof KmrPascalCallExpression && ((KmrPascalCallExpression) e.getParent()).getExpression() == e;
				boolean insideOwnBody = target instanceof KmrPascalFunctionDeclaration && PsiTreeUtil.isAncestor(target, e, true);
				if (callable != null && callable.isFunction() && !callee && (callable.getParameterCount() == 0 || insideOwnBody)) {
					return resultKind(target, path);
				}
				return null;
			}
			return kindAt(KmrTypeProvider.typeOf(e), path.size());
		}
		if (e instanceof KmrPascalCallExpression) {
			PsiElement target = KmrPascalTypeUtil.resolveSingle(((KmrPascalCallExpression) e).getExpression());
			if (target != null && isInferable(target) && isCallable(target)) {
				return resultKind(target, path); // user functions and API stub functions (their @kind tag)
			}
			return kindAt(KmrTypeProvider.typeOf(e), path.size()); // casts (no kind), enum values, ...
		}
		if (e instanceof KmrPascalIndexExpression) {
			Slot slot = slotOf(e);
			if (slot != null) {
				Slot target = slot.deeper(path);
				KmrKind declared = kindAt(KmrTypeProvider.declaredTypeOf(slot.declaration), target.depth);
				return declared != null ? declared : resultOf(target).valueKind();
			}
			List<KmrPascalExpression> children = ((KmrPascalIndexExpression) e).getExpressionList();
			if (children.isEmpty()) {
				return null;
			}
			List<String> extended = new ArrayList<>();
			for (int i = 1; i < children.size(); i++) {
				extended.add(indexKey(children.get(i)));
			}
			extended.addAll(path);
			return kindOf(children.get(0), extended);
		}
		if (!path.isEmpty()) {
			return null;
		}
		if (e instanceof KmrPascalAdditiveExpression || e instanceof KmrPascalMultiplicativeExpression) {
			List<KmrPascalExpression> operands = e instanceof KmrPascalAdditiveExpression
				? ((KmrPascalAdditiveExpression) e).getExpressionList() : ((KmrPascalMultiplicativeExpression) e).getExpressionList();
			IElementType operator = KmrTypeProvider.operatorOf(e);
			if (operands.size() != 2 || operator == null || !KmrTypeProvider.typeOf(e).is(KmrType.Kind.INTEGER)) {
				return null;
			}
			KmrKind left = kindOf(operands.get(0), Collections.emptyList());
			KmrKind right = kindOf(operands.get(1), Collections.emptyList());
			if (left == MIXED || right == MIXED) {
				return MIXED;
			}
			return KmrTypeProvider.arithmeticKind(operator, left, right);
		}
		return null; // literals, unary minus (-1 is a sentinel), comparisons, @routine, [lists]
	}

	/**
	 * The {@code @kind} annotation of an expression: a comment directly in front of it, or, for the value of an
	 * assignment, the comment above the assignment. The kind is that of the innermost integer of the expression's
	 * type (an array's element), like a tag on a declaration.
	 */
	@Nullable
	private static Explicit annotationOf(@NotNull KmrPascalExpression expression)
	{
		int start = expression.getTextRange().getStartOffset();
		PsiElement parent = expression.getParent();
		if (parent instanceof KmrPascalExpression && parent.getTextRange().getStartOffset() == start) {
			return null; // a comment in front of "Stash[I]" annotates the whole index expression, not "Stash"
		}
		// the parser keeps a leading comment outside the argument list: "(", comment, argument list
		KmrPascalDocComment doc = KmrPascalDocComment.of(expression);
		for (PsiElement element = parent; doc == null && (element instanceof KmrPascalFunctionArgumentList || element instanceof KmrPascalIndexList)
			&& element.getTextRange().getStartOffset() == start; element = element.getParent()) {
			doc = KmrPascalDocComment.of(element);
		}
		if (doc == null && parent instanceof KmrPascalAssignment) {
			List<KmrPascalExpression> sides = ((KmrPascalAssignment) expression.getParent()).getExpressionList();
			if (sides.size() == 2 && sides.get(1) == expression) {
				doc = KmrPascalDocComment.of(expression.getParent());
			}
		}
		String value = doc == null ? null : doc.getTagValue(KmrPascalDocComment.TAG_KIND);
		if (value == null) {
			return null;
		}
		return new Explicit(kindFromTag(value, expression), arrayDepth(KmrTypeProvider.typeOf(expression)));
	}

	/**
	 * The kind of a declaration's value {@code levels} array levels deep: what its declared type says (a kind alias),
	 * else the explicit/inferred kind; null when unknown or conflicting. For callables this is the kind of the result.
	 */
	@Nullable
	public static KmrKind kindOfDeclaration(@NotNull PsiElement declaration, int levels)
	{
		return known(declarationKind(declaration, dynamicPath(levels)));
	}

	/** {@link #kindOfDeclaration} along an index path; {@link #MIXED} for a mixed value. */
	@Nullable
	private static KmrKind declarationKind(@NotNull PsiElement declaration, @NotNull List<String> path)
	{
		if (isCallable(declaration)) {
			return resultKind(declaration, path);
		}
		KmrKind declared = kindAt(KmrTypeProvider.declaredTypeOf(declaration), path.size());
		return declared != null ? declared : resultOf(declaration, path).valueKind();
	}

	/** The kind of a function's result: its declared return type (alias), else its {@code @kind} tag / inference. */
	@Nullable
	public static KmrKind resultKindOf(@NotNull PsiElement callableDeclaration, int levels)
	{
		return known(resultKind(callableDeclaration, dynamicPath(levels)));
	}

	@Nullable
	private static KmrKind resultKind(@NotNull PsiElement callableDeclaration, @NotNull List<String> path)
	{
		KmrPascalCallable callable = KmrPascalCallable.of(callableDeclaration);
		KmrKind declared = callable == null ? null : kindAt(KmrTypeProvider.returnTypeOf(callable), path.size());
		return declared != null ? declared : resultOf(callableDeclaration, path).valueKind();
	}

	/** A routine or a procedural-type variable/field (the form the API stubs use). */
	static boolean isCallable(@NotNull PsiElement declaration)
	{
		return declaration instanceof KmrPascalRoutineDeclaration || KmrPascalCallable.of(declaration) != null;
	}

	/** The inferred kind of a declaration's value ({@code depth} index levels deep), or null when unknown or conflicting. */
	@Nullable
	public static KmrKind inferredKindOf(@NotNull PsiElement declaration, int depth)
	{
		return resultOf(declaration, depth).kind;
	}

	@NotNull
	public static Result resultOf(@NotNull PsiElement declaration, int depth)
	{
		return resultOf(Slot.dynamic(declaration, depth));
	}

	@NotNull
	private static Result resultOf(@NotNull PsiElement declaration, @NotNull List<String> path)
	{
		return resultOf(new Slot(declaration, path.size(), path));
	}

	@NotNull
	public static Result resultOf(@NotNull Slot slot)
	{
		PsiElement declaration = slot.declaration;
		if (!isInferable(declaration)) {
			return Result.UNKNOWN;
		}
		Map<String, Result> cache = CachedValuesManager.getCachedValue(declaration, RESULTS,
			() -> CachedValueProvider.Result.create(new ConcurrentHashMap<>(), PsiModificationTracker.MODIFICATION_COUNT));
		String cacheKey = slot.cacheKey();
		Result cached = cache.get(cacheKey);
		if (cached != null) {
			return cached;
		}
		List<Frame> stack = STACK.get();
		for (int i = 0; i < stack.size(); i++) {
			if (stack.get(i).slot.sameAs(slot)) {
				// re-entered: everything computed inside this slot's computation saw an incomplete answer
				for (int j = i + 1; j < stack.size(); j++) {
					stack.get(j).incomplete = true;
				}
				return Result.UNKNOWN;
			}
		}
		Frame frame = new Frame(slot);
		stack.add(frame);
		Result computed;
		try {
			computed = compute(slot);
		} finally {
			stack.remove(stack.size() - 1);
		}
		if (!frame.incomplete) {
			cache.put(cacheKey, computed);
		}
		return computed;
	}

	/**
	 * The slot an expression denotes ({@code Units[I].X} is the field X at depth 0, {@code Grid[1][2]} is Grid at
	 * depth 2 with keys "1", "2"), or null when it is not built on an inferable declaration.
	 */
	@Nullable
	public static Slot slotOf(@Nullable KmrPascalExpression lvalue)
	{
		List<String> keys = new ArrayList<>();
		KmrPascalExpression expression = lvalue;
		while (expression != null) {
			if (expression instanceof KmrPascalParenExpression) {
				expression = ((KmrPascalParenExpression) expression).getExpression();
			} else if (expression instanceof KmrPascalIndexExpression) {
				List<KmrPascalExpression> children = ((KmrPascalIndexExpression) expression).getExpressionList();
				if (children.isEmpty()) {
					return null;
				}
				List<String> level = new ArrayList<>();
				for (int i = 1; i < children.size(); i++) {
					level.add(indexKey(children.get(i)));
				}
				keys.addAll(0, level); // inner indexes come first
				expression = children.get(0);
			} else {
				break;
			}
		}
		if (!(expression instanceof KmrPascalReferenceExpression)) {
			return null;
		}
		PsiElement target = KmrPascalTypeUtil.resolveSingle(expression);
		return target != null && isInferable(target) ? new Slot(target, keys.size(), keys) : null; // a function is the slot of its Result
	}

	/** A normalized key for a constant index expression (literal, constant, enum value), or null when it is computed. */
	@Nullable
	static String indexKey(@NotNull KmrPascalExpression index)
	{
		KmrPascalExpression e = index;
		while (e instanceof KmrPascalParenExpression) {
			e = ((KmrPascalParenExpression) e).getExpression();
		}
		if (e instanceof KmrPascalLiteralExpression) {
			return literalKey(e.getText());
		}
		if (e instanceof KmrPascalUnaryExpression && e.getText().startsWith("-") && ((KmrPascalUnaryExpression) e).getExpression() instanceof KmrPascalLiteralExpression) {
			String key = literalKey(((KmrPascalUnaryExpression) e).getExpression().getText());
			return key == null ? null : "-" + key;
		}
		if (e instanceof KmrPascalReferenceExpression) {
			PsiElement target = KmrPascalTypeUtil.resolveSingle(e);
			if (target instanceof KmrPascalConstantDeclaration) {
				KmrPascalExpression value = ((KmrPascalConstantDeclaration) target).getExpression();
				String key = value == null ? null : indexKey(value);
				return key != null ? key : "const:" + String.valueOf(((KmrPascalConstantDeclaration) target).getName()).toLowerCase(Locale.ROOT);
			}
			if (target instanceof KmrPascalEnumValue) {
				return "enum:" + String.valueOf(((KmrPascalEnumValue) target).getName()).toLowerCase(Locale.ROOT);
			}
		}
		return null;
	}

	@Nullable
	private static String literalKey(@NotNull String text)
	{
		try {
			return text.startsWith("$") ? String.valueOf(Long.parseLong(text.substring(1), 16)) : String.valueOf(Long.parseLong(text));
		} catch (NumberFormatException e) {
			return text.startsWith("'") ? text : null;
		}
	}

	/** The result of the slot an l-value denotes, or null when it denotes no inferable declaration. */
	@Nullable
	public static Result resultOf(@Nullable KmrPascalExpression lvalue)
	{
		Slot slot = slotOf(lvalue);
		return slot == null ? null : resultOf(slot);
	}

	public static boolean isInferable(@NotNull PsiElement declaration)
	{
		return declaration instanceof KmrPascalTypedIdentifier || declaration instanceof KmrPascalRoutineDeclaration
			|| declaration instanceof KmrPascalConstantDeclaration;
	}

	/**
	 * The kind a {@code @kind} tag value denotes: {@code unitId}/{@code TKMUnitID}/{@code unit ID} name a stub kind
	 * of the file's game version, {@code none} means "no kind" (null), anything else is a free label.
	 */
	@Nullable
	public static KmrKind kindFromTag(@NotNull String value, @NotNull PsiElement context)
	{
		String label = value.trim();
		if (label.isEmpty() || NO_KIND_LABELS.contains(label.toLowerCase(Locale.ROOT))) {
			return null;
		}
		if (label.contains("|")) {
			// "@kind aId unitId|houseId": a value that may be either
			List<KmrKind> members = new ArrayList<>();
			for (String part : label.split("\\|")) {
				KmrKind member = kindFromTag(part, context);
				if (member != null) {
					members.add(member);
				}
			}
			return members.isEmpty() ? null : KmrKind.union(members);
		}
		KmrKind stubKind = findStubKind(label, context);
		return stubKind != null ? stubKind : KmrKind.label(label);
	}

	/** The kind declared by a (stub or user) alias type declaration through its {@code @kind} tag, or null. */
	@Nullable
	public static KmrKind declaredKindOf(@NotNull KmrPascalTypeDeclaration declaration)
	{
		String name = declaration.getName();
		if (name == null) {
			return null;
		}
		KmrKind own = KmrKind.fromDeclaration(declaration, name);
		if (own != null) {
			return own;
		}
		KmrPascalDocComment doc = KmrPascalDocComment.of(declaration);
		String value = doc == null ? null : doc.getTagValue(KmrPascalDocComment.TAG_KIND);
		return value == null ? null : kindFromTag(value, declaration);
	}

	/** The kind-declaring alias types of the game version used at the given place. */
	@NotNull
	public static List<KmrKind> stubKinds(@NotNull PsiElement context)
	{
		PsiFile file = context.getContainingFile();
		if (file == null) {
			return Collections.emptyList();
		}
		KmrPascalStubScope scope = KmrPascalStubScope.getInstance(context.getProject());
		List<KmrKind> result = new ArrayList<>();
		for (List<KmrPascalStubScope.Declaration> declarations : scope.declarations(scope.versionFor(file.getOriginalFile())).values()) {
			for (KmrPascalStubScope.Declaration declaration : declarations) {
				if (declaration.element instanceof KmrPascalTypeDeclaration) {
					KmrKind kind = KmrKind.fromDeclaration(declaration.element, String.valueOf(declaration.element.getName()));
					if (kind != null) {
						result.add(kind);
					}
				}
			}
		}
		return result;
	}

	@Nullable
	private static KmrKind findStubKind(@NotNull String label, @NotNull PsiElement context)
	{
		String wanted = label.replace(" ", "").toLowerCase(Locale.ROOT);
		for (KmrKind kind : stubKinds(context)) {
			String name = kind.name.toLowerCase(Locale.ROOT);
			if (name.equals(wanted) || name.startsWith("tkm") && name.substring(3).equals(wanted)
				|| kind.label.replace(" ", "").toLowerCase(Locale.ROOT).equals(wanted)) {
				return kind;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// computation
	// ---------------------------------------------------------------------------------------------------------------

	@NotNull
	private static Result compute(@NotNull Slot slot)
	{
		PsiElement declaration = slot.declaration;
		int depth = slot.depth;
		// 1. explicit tag (targets the innermost integer of the declared type: an array's element)
		Explicit explicit = explicitKind(declaration);
		if (explicit != null && explicit.depth == depth) {
			return explicit.kind == null ? Result.UNKNOWN : Result.single(explicit.kind);
		}
		if (KmrPascalStubLibrary.isStubFile(declaration.getContainingFile())) {
			return Result.UNKNOWN; // the API stubs declare kinds only through tags and alias types
		}
		// 2. event handler parameter
		if (depth == 0 && declaration instanceof KmrPascalParameterIdentifier) {
			KmrKind eventKind = eventParameterKind((KmrPascalParameterIdentifier) declaration);
			if (eventKind != null) {
				return Result.single(eventKind);
			}
		}
		// 3. flow: writes through the same static indexes decide (and may conflict); writes through computed indexes
		//    only contribute when nothing more precise exists, and never conflict but leave the slot mixed
		Sources sources = collectSources(slot);
		if (slot.isStatic() && !sources.exact.isEmpty()) {
			return resolve(sources.exact, true);
		}
		List<Source> all = new ArrayList<>(sources.exact);
		all.addAll(sources.fuzzy);
		return resolve(all, false);
	}

	/**
	 * One consistent kind wins. A mixed source (an element of a heterogeneous array read through a computed index)
	 * makes a loosely matched slot mixed as well; for exactly matched static writes it is ignored when other sources
	 * exist, since those name the kind the place really holds.
	 */
	@NotNull
	private static Result resolve(@NotNull List<Source> sources, boolean reportConflicts)
	{
		List<Source> known = new ArrayList<>();
		boolean mixed = false;
		for (Source source : sources) {
			if (source.kind == MIXED) {
				mixed = true;
			} else {
				known.add(source);
			}
		}
		if (known.isEmpty() || mixed && !reportConflicts) {
			return mixed ? Result.MIXED_RESULT : Result.UNKNOWN;
		}
		KmrKind first = known.get(0).kind;
		for (Source source : known) {
			if (!source.kind.compatible(first)) {
				return reportConflicts ? new Result(null, true, known, false) : Result.MIXED_RESULT;
			}
		}
		return Result.single(first);
	}

	private static final class Explicit
	{
		@Nullable
		final KmrKind kind;
		final int depth;

		Explicit(@Nullable KmrKind kind, int depth)
		{
			this.kind = kind;
			this.depth = depth;
		}
	}

	/**
	 * The {@code @kind} tag written for the declaration, or null when there is none. A tag {@code @kind <name> <kind>}
	 * whose first word names one of the identifiers of the group (parameters of the routine or procedural-type field,
	 * names of a {@code X, Y: Integer} declaration) targets that identifier; a tag without such a name targets the
	 * declaration that carries the comment (a variable, field, constant, or the result of a routine).
	 */
	@Nullable
	private static Explicit explicitKind(@NotNull PsiElement declaration)
	{
		String ownName = declaration instanceof KmrPascalNamedElement ? ((KmrPascalNamedElement) declaration).getName() : null;
		if (ownName == null) {
			return null;
		}
		boolean parameter = declaration instanceof KmrPascalParameterIdentifier;
		PsiElement owner = parameter ? callableOwner(declaration) : declaration;
		KmrPascalDocComment doc = owner == null ? null : KmrPascalDocComment.of(owner);
		if (doc == null) {
			return null;
		}
		Set<String> groupNames = groupNames(owner);
		String value = null;
		for (String tag : doc.getTagValues(KmrPascalDocComment.TAG_KIND)) {
			int space = tag.indexOf(' ');
			String first = space > 0 ? tag.substring(0, space) : tag;
			boolean targeted = space > 0 && groupNames.contains(first.toLowerCase(Locale.ROOT));
			if (targeted ? first.equalsIgnoreCase(ownName) : !parameter) {
				value = targeted ? tag.substring(space + 1).trim() : tag;
			}
		}
		if (value == null) {
			return null;
		}
		KmrType type = isCallable(declaration) ? KmrTypeProvider.returnTypeOf(KmrPascalCallable.of(declaration)) : KmrTypeProvider.declaredTypeOf(declaration);
		return new Explicit(kindFromTag(value, declaration), arrayDepth(type));
	}

	/** The routine or procedural-type variable/field whose parameter list contains the parameter. */
	@Nullable
	private static PsiElement callableOwner(@NotNull PsiElement parameter)
	{
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(parameter, KmrPascalRoutineDeclaration.class);
		KmrPascalTypeSpec typeSpec = PsiTreeUtil.getParentOfType(parameter, KmrPascalTypeSpec.class);
		if (typeSpec != null && (routine == null || PsiTreeUtil.isAncestor(routine, typeSpec, true))) {
			// a parameter of a procedural type: "ShowMsg: procedure(aHand: Integer)" -> the field/variable ShowMsg
			PsiElement declaration = typeSpec.getParent();
			return declaration == null ? null : PsiTreeUtil.getChildOfType(declaration, KmrPascalTypedIdentifier.class);
		}
		return routine;
	}

	/** Lower-cased names a targeted tag may address: parameters of a callable, or the identifiers of a declaration group. */
	@NotNull
	private static Set<String> groupNames(@NotNull PsiElement owner)
	{
		Set<String> names = new HashSet<>();
		KmrPascalCallable callable = KmrPascalCallable.of(owner);
		if (callable != null) {
			for (KmrPascalParameterIdentifier parameter : callable.getParameterIdentifiers()) {
				if (parameter.getName() != null) {
					names.add(parameter.getName().toLowerCase(Locale.ROOT));
				}
			}
		}
		if (owner instanceof KmrPascalTypedIdentifier && owner.getParent() != null) {
			for (KmrPascalTypedIdentifier sibling : PsiTreeUtil.getChildrenOfTypeAsList(owner.getParent(), KmrPascalTypedIdentifier.class)) {
				if (sibling.getName() != null) {
					names.add(sibling.getName().toLowerCase(Locale.ROOT));
				}
			}
		}
		return names;
	}

	private static int arrayDepth(@NotNull KmrType type)
	{
		int depth = 0;
		while (type.is(KmrType.Kind.ARRAY) && type.element != null) {
			depth++;
			type = type.element;
		}
		return depth;
	}

	@Nullable
	private static KmrKind eventParameterKind(@NotNull KmrPascalParameterIdentifier parameter)
	{
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(parameter, KmrPascalRoutineDeclaration.class);
		if (routine == null) {
			return null;
		}
		KmrPascalEvent event = KmrPascalEvents.getInstance(parameter.getProject()).findForRoutine(routine);
		if (event == null) {
			return null;
		}
		KmrPascalCallable own = KmrPascalCallable.of(routine);
		int index = own == null ? -1 : own.getParameterIdentifiers().indexOf(parameter);
		List<KmrPascalParameterIdentifier> expected = event.getSignature().getParameterIdentifiers();
		if (index < 0 || index >= expected.size()) {
			return null;
		}
		return kindOfDeclaration(expected.get(index), 0);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// flow: assignment sites of the compilation unit
	// ---------------------------------------------------------------------------------------------------------------

	/** Everything in a unit that may assign to a slot, keyed by the lower-cased name it mentions (no resolving). */
	private static final class Sites
	{
		final Map<String, List<KmrPascalAssignment>> assignments = new HashMap<>();
		final Map<String, List<KmrPascalForStatement>> loops = new HashMap<>();
		/** Calls keyed by the callee's name (arguments flow into the parameters). */
		final Map<String, List<KmrPascalCallExpression>> callsByCallee = new HashMap<>();
		/** Calls keyed by the base name of each l-value argument (var/out parameters flow into the argument). */
		final Map<String, List<KmrPascalCallExpression>> callsByArgument = new HashMap<>();

		static <T> void add(Map<String, List<T>> map, @Nullable String key, T value)
		{
			if (key != null) {
				map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
			}
		}
	}

	@NotNull
	private static Sites sitesOf(@NotNull KmrPascalCompilationUnit unit)
	{
		PsiFile entryPoint = unit.getEntryPoint();
		return CachedValuesManager.getCachedValue(entryPoint, SITES, () -> {
			KmrPascalCompilationUnit current = KmrPascalCompilationUnit.of(entryPoint);
			return CachedValueProvider.Result.create(collectSites(current), PsiModificationTracker.MODIFICATION_COUNT);
		});
	}

	@NotNull
	private static Sites collectSites(@NotNull KmrPascalCompilationUnit unit)
	{
		Sites sites = new Sites();
		Set<PsiFile> seen = new HashSet<>();
		for (KmrPascalCompilationUnit.Inclusion inclusion : unit.getInclusions()) {
			if (!seen.add(inclusion.file)) {
				continue;
			}
			PsiTreeUtil.processElements(inclusion.file, element -> {
				if (element instanceof KmrPascalAssignment) {
					List<KmrPascalExpression> sides = ((KmrPascalAssignment) element).getExpressionList();
					if (sides.size() == 2 && unit.isActive(element)) {
						Sites.add(sites.assignments, baseName(sides.get(0)), (KmrPascalAssignment) element);
					}
				} else if (element instanceof KmrPascalForStatement) {
					List<KmrPascalExpression> parts = ((KmrPascalForStatement) element).getExpressionList();
					if (parts.size() >= 3 && unit.isActive(element)) {
						Sites.add(sites.loops, baseName(parts.get(0)), (KmrPascalForStatement) element);
					}
				} else if (element instanceof KmrPascalCallExpression) {
					KmrPascalCallExpression call = (KmrPascalCallExpression) element;
					if (call.getFunctionArgumentList() != null && unit.isActive(element)) {
						Sites.add(sites.callsByCallee, baseName(call.getExpression()), call);
						for (KmrPascalExpression argument : call.getFunctionArgumentList().getExpressionList()) {
							Sites.add(sites.callsByArgument, baseName(argument), call);
						}
					}
				}
				return true;
			});
		}
		return sites;
	}

	/** Lower-cased name of the reference an l-value is built on ({@code Units[I].X} -> "x", {@code Grid[1]} -> "grid"). */
	@Nullable
	private static String baseName(@Nullable KmrPascalExpression expression)
	{
		while (expression != null) {
			if (expression instanceof KmrPascalParenExpression) {
				expression = ((KmrPascalParenExpression) expression).getExpression();
			} else if (expression instanceof KmrPascalIndexExpression) {
				List<KmrPascalExpression> children = ((KmrPascalIndexExpression) expression).getExpressionList();
				expression = children.isEmpty() ? null : children.get(0);
			} else {
				break;
			}
		}
		if (expression instanceof KmrPascalReferenceExpression) {
			String name = ((KmrPascalReferenceExpression) expression).getReferenceName();
			return name.isEmpty() ? null : name.toLowerCase(Locale.ROOT);
		}
		return null;
	}

	/** Kinded values flowing into a slot: through matching static indexes ({@code exact}) or computed ones ({@code fuzzy}). */
	private static final class Sources
	{
		final List<Source> exact = new ArrayList<>();
		final List<Source> fuzzy = new ArrayList<>();

		void add(@NotNull Match match, @Nullable Source source)
		{
			if (source != null) {
				(match.exact ? exact : fuzzy).add(source);
			}
		}
	}

	/** How a write site relates to the slot being computed. */
	private static final class Match
	{
		/** The slot's index path below the site's place (empty when the site writes the slot itself). */
		final List<String> path;
		/** All indexes on the way are the same constants (or the site writes the whole variable). */
		final boolean exact;

		Match(@NotNull List<String> path, boolean exact)
		{
			this.path = path;
			this.exact = exact;
		}
	}

	@NotNull
	private static Sources collectSources(@NotNull Slot slot)
	{
		PsiElement declaration = slot.declaration;
		Sources sources = new Sources();
		PsiFile file = declaration.getContainingFile();
		if (file == null || !(declaration instanceof KmrPascalNamedElement) || ((KmrPascalNamedElement) declaration).getName() == null) {
			return sources;
		}
		KmrPascalCompilationUnit unit = KmrPascalResolveContextService.getInstance(declaration.getProject()).getUnitFor(file.getOriginalFile());
		Sites sites = sitesOf(unit);
		String name = ((KmrPascalNamedElement) declaration).getName().toLowerCase(Locale.ROOT);
		List<String> names = declaration instanceof KmrPascalFunctionDeclaration ? List.of(name, "result") : List.of(name);

		for (String key : names) {
			for (KmrPascalAssignment assignment : sites.assignments.getOrDefault(key, Collections.emptyList())) {
				List<KmrPascalExpression> sides = assignment.getExpressionList();
				Match match = match(slotOf(sides.get(0)), slot);
				if (match != null) {
					sources.add(match, source(sides.get(1), match.path));
				}
			}
			for (KmrPascalForStatement loop : sites.loops.getOrDefault(key, Collections.emptyList())) {
				List<KmrPascalExpression> parts = loop.getExpressionList();
				Match match = match(slotOf(parts.get(0)), slot);
				if (match != null) {
					sources.add(match, source(parts.get(1), match.path));
					sources.add(match, source(parts.get(2), match.path));
				}
			}
			for (KmrPascalCallExpression call : sites.callsByArgument.getOrDefault(key, Collections.emptyList())) {
				addVarOutSources(sources, call, slot);
			}
		}
		if (declaration instanceof KmrPascalParameterIdentifier) {
			addArgumentSources(sources, (KmrPascalParameterIdentifier) declaration, slot, sites);
		}
		return sources;
	}

	/**
	 * Whether a write to {@code site} may reach {@code slot}: same declaration, the site not deeper than the slot, and
	 * no two constant indexes on the way that differ. Exact when no computed index is involved. The slot's remaining
	 * keys below the site travel on into the written value ({@code Rows[I] := Attack} reaches {@code Rows[I][3]}
	 * through {@code Attack[3]}).
	 */
	@Nullable
	private static Match match(@Nullable Slot site, @NotNull Slot slot)
	{
		if (site == null || site.depth > slot.depth || !equivalent(site.declaration, slot.declaration)) {
			return null;
		}
		boolean exact = true;
		for (int i = 0; i < site.depth; i++) {
			String a = site.keys.get(i);
			String b = slot.keys.get(i);
			if (a == null || b == null) {
				exact = false;
			} else if (!a.equals(b)) {
				return null;
			}
		}
		return new Match(slot.keys.subList(site.depth, slot.depth), exact);
	}

	private static boolean equivalent(@NotNull PsiElement a, @NotNull PsiElement b)
	{
		return a == b || a.getManager().areElementsEquivalent(a, b);
	}

	@Nullable
	private static Source source(@Nullable KmrPascalExpression value, @NotNull List<String> path)
	{
		KmrKind kind = value == null ? null : kindOf(value, path);
		return kind == null ? null : new Source(value, kind);
	}

	/** The value kind {@code levels} array levels inside a type. */
	@Nullable
	static KmrKind kindAt(@NotNull KmrType type, int levels)
	{
		for (int i = 0; i < levels; i++) {
			if (!type.is(KmrType.Kind.ARRAY) || type.element == null) {
				return null;
			}
			type = type.element;
		}
		return type.valueKind;
	}

	/** {@code Foo(X)} with {@code var}/{@code out} parameter: the parameter's kind flows into X. */
	private static void addVarOutSources(@NotNull Sources sources, @NotNull KmrPascalCallExpression call, @NotNull Slot slot)
	{
		KmrPascalCallable callable = KmrPascalCallable.of(KmrPascalTypeUtil.resolveSingle(call.getExpression()));
		KmrPascalFunctionArgumentList arguments = call.getFunctionArgumentList();
		if (callable == null || arguments == null) {
			return;
		}
		List<KmrPascalParameterIdentifier> parameters = callable.getParameterIdentifiers();
		List<KmrPascalExpression> values = arguments.getExpressionList();
		for (int i = 0; i < Math.min(parameters.size(), values.size()); i++) {
			KmrPascalArgumentModifier modifier = ((KmrPascalParameterDeclaration) parameters.get(i).getParent()).getArgumentModifier();
			if (modifier == null || modifier.getText().equalsIgnoreCase("const")) {
				continue;
			}
			Match match = match(slotOf(values.get(i)), slot);
			if (match != null) {
				KmrKind kind = declarationKind(parameters.get(i), match.path);
				sources.add(match, kind == null ? null : new Source(values.get(i), kind));
			}
		}
	}

	/** Arguments passed for a user routine's parameter flow into the parameter. */
	private static void addArgumentSources(@NotNull Sources sources, @NotNull KmrPascalParameterIdentifier parameter, @NotNull Slot slot, @NotNull Sites sites)
	{
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(parameter, KmrPascalRoutineDeclaration.class);
		KmrPascalCallable own = routine == null ? null : KmrPascalCallable.of(routine);
		String routineName = routine == null ? null : routine.getName();
		if (own == null || routineName == null) {
			return;
		}
		int index = own.getParameterIdentifiers().indexOf(parameter);
		if (index < 0) {
			return;
		}
		for (KmrPascalCallExpression call : sites.callsByCallee.getOrDefault(routineName.toLowerCase(Locale.ROOT), Collections.emptyList())) {
			KmrPascalFunctionArgumentList arguments = call.getFunctionArgumentList();
			if (arguments == null || arguments.getExpressionList().size() <= index) {
				continue;
			}
			PsiElement target = KmrPascalTypeUtil.resolveSingle(call.getExpression());
			if (target != null && equivalent(target, routine)) {
				sources.add(new Match(slot.keys, slot.isStatic()), source(arguments.getExpressionList().get(index), slot.keys));
			}
		}
	}

}
