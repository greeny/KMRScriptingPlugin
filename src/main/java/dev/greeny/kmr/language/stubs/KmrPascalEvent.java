package dev.greeny.kmr.language.stubs;

import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.psi.KmrPascalDocComment;
import dev.greeny.kmr.language.psi.KmrPascalFieldIdentifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

	@Override
	public String toString()
	{
		return handlerName + " (" + eventName + ")";
	}

}
