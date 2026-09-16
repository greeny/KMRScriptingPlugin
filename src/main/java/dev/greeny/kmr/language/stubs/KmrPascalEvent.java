package dev.greeny.kmr.language.stubs;

import dev.greeny.kmr.language.psi.KmrPascalDocComment;
import dev.greeny.kmr.language.psi.KmrPascalFieldIdentifier;
import dev.greeny.kmr.language.psi.KmrPascalParameterIdentifier;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.types.KmrTypePresenter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/** One game event as described by a field of {@code TKMScriptEvents} in the Events stub. */
public final class KmrPascalEvent
{

	/** Conventional handler procedure name, e.g. {@code OnHouseBuilt}. */
	public final String handlerName;
	/** Name used in {@code {$EVENT name:handler}}, e.g. {@code evtHouseBuilt}. */
	public final String eventName;
	/** The stub field; its type is the required handler signature. */
	public final KmrPascalFieldIdentifier field;
	@Nullable
	public final KmrPascalDocComment doc;

	KmrPascalEvent(@NotNull String handlerName, @NotNull String eventName, @NotNull KmrPascalFieldIdentifier field, @Nullable KmrPascalDocComment doc)
	{
		this.handlerName = handlerName;
		this.eventName = eventName;
		this.field = field;
		this.doc = doc;
	}

	@NotNull
	public KmrPascalCallable getSignature()
	{
		KmrPascalCallable callable = KmrPascalCallable.of(field);
		assert callable != null : "event field without procedural type: " + field.getText();
		return callable;
	}

	/** Whether a routine can handle this event: a procedure with the same parameter types (names may differ). */
	public boolean accepts(@NotNull KmrPascalCallable actual)
	{
		KmrPascalCallable expected = getSignature();
		if (actual.isFunction() != expected.isFunction()) {
			return false;
		}
		List<KmrPascalParameterIdentifier> expectedParameters = expected.getParameterIdentifiers();
		List<KmrPascalParameterIdentifier> actualParameters = actual.getParameterIdentifiers();
		if (expectedParameters.size() != actualParameters.size()) {
			return false;
		}
		for (int i = 0; i < expectedParameters.size(); i++) {
			if (!typeOf(expectedParameters.get(i)).equals(typeOf(actualParameters.get(i)))) {
				return false;
			}
		}
		return true;
	}

	/** Plain type text: the stubs' kind aliases ({@code TKMHouseID}) compare as their underlying type. */
	@NotNull
	private static String typeOf(@NotNull KmrPascalParameterIdentifier parameter)
	{
		return KmrTypePresenter.plainTypeText(parameter.getType()).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString()
	{
		return handlerName + " (" + eventName + ")";
	}

}
