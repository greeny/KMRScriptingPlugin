package dev.greeny.kmr.language.unit;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.indexing.*;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import dev.greeny.kmr.language.KmrPascalFileType;
import dev.greeny.kmr.language.KmrPascalLexerAdapter;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps the lower-cased file name of every {@code {$I ...}} argument to the files containing that directive,
 * so "which files include X" is a cheap reverse lookup.
 */
public class KmrPascalIncludeIndex extends ScalarIndexExtension<String>
{

	public static final ID<String, Void> NAME = ID.create("dev.greeny.kmr.KmrPascalIncludeIndex");

	/**
	 * Files containing an include directive for the given file name. Empty while indexing (dumb mode): callers
	 * that cache the result must depend on {@code DumbService.getModificationTracker()} so they recompute afterwards.
	 */
	@NotNull
	public static Collection<VirtualFile> filesIncluding(@NotNull Project project, @NotNull String fileName)
	{
		if (DumbService.isDumb(project)) {
			return Collections.emptyList();
		}
		try {
			return FileBasedIndex.getInstance().getContainingFiles(NAME, KmrPascalDirective.includeKey(fileName), GlobalSearchScope.allScope(project));
		} catch (IndexNotReadyException e) {
			return Collections.emptyList();
		}
	}

	@Override
	public @NotNull ID<String, Void> getName()
	{
		return NAME;
	}

	@Override
	public @NotNull DataIndexer<String, Void, FileContent> getIndexer()
	{
		return inputData -> {
			Map<String, Void> result = new HashMap<>();
			Lexer lexer = new KmrPascalLexerAdapter();
			lexer.start(inputData.getContentAsText());
			for (IElementType type = lexer.getTokenType(); type != null; lexer.advance(), type = lexer.getTokenType()) {
				if (type == KmrPascalTypes.DIRECTIVE_INCLUDE) {
					KmrPascalDirective directive = KmrPascalDirective.parse(type, lexer.getTokenText(), lexer.getTokenStart());
					if (!directive.argument.isEmpty()) {
						result.put(KmrPascalDirective.includeKey(directive.argument), null);
					}
				}
			}
			return result;
		};
	}

	@Override
	public @NotNull KeyDescriptor<String> getKeyDescriptor()
	{
		return EnumeratorStringDescriptor.INSTANCE;
	}

	@Override
	public int getVersion()
	{
		return 1;
	}

	@Override
	public FileBasedIndex.@NotNull InputFilter getInputFilter()
	{
		return new DefaultFileTypeSpecificInputFilter(KmrPascalFileType.INSTANCE);
	}

	@Override
	public boolean dependsOnFileContent()
	{
		return true;
	}

}
