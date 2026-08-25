package software.coley.recaf.services.mapping;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.DummyEnum;
import software.coley.recaf.test.dummy.DummyEnumPrinter;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class MappingPrefilterTest extends TestBase {
	@Test
	void ownerPrefilterMatchesFullAsmForMemberOnlyMappings() throws IOException {
		Workspace filteredWorkspace = newWorkspace();
		Workspace fullWorkspace = newWorkspace();
		String printer = DummyEnumPrinter.class.getName().replace('.', '/');

		MappingsAdapter filteredMappings = memberMappings(printer);
		MappingsAdapter fullMappingsDelegate = memberMappings(printer);
		Mappings fullMappings = new DelegatingMappings(fullMappingsDelegate);

		MappingApplierService service = recaf.get(MappingApplierService.class);
		MappingResults filtered = service.inWorkspace(filteredWorkspace).applyToPrimaryResource(filteredMappings);
		MappingResults full = service.inWorkspace(fullWorkspace).applyToPrimaryResource(fullMappings);

		assertEquals(full.getMappedClasses(), filtered.getMappedClasses());
		for (String originalName : full.getMappedClasses().keySet()) {
			JvmClassInfo expected = full.getPostMappingClass(originalName).asJvmClass();
			JvmClassInfo actual = filtered.getPostMappingClass(originalName).asJvmClass();
			assertArrayEquals(expected.getBytecode(), actual.getBytecode(),
					"Prefilter changed mapping output for " + originalName);
		}
		assertTrue(filtered.wasMapped(printer), "Member-only owner was incorrectly filtered out");
	}

	@Nonnull
	private static Workspace newWorkspace() throws IOException {
		return TestClassUtils.fromBundle(TestClassUtils.fromClasses(
				DummyEnum.class, DummyEnumPrinter.class, HelloWorld.class));
	}

	@Nonnull
	private static MappingsAdapter memberMappings(@Nonnull String printer) {
		MappingsAdapter mappings = new MappingsAdapter(true, true);
		mappings.addMethod(printer, "run1", "()Ljava/lang/String;", "renamedRun");
		return mappings;
	}

	/**
	 * Hides explicit owner metadata from the applier, forcing the full ASM path while preserving mapping behavior.
	 */
	private record DelegatingMappings(Mappings delegate) implements Mappings {
		@Nullable
		@Override
		public String getMappedClassName(@Nonnull String internalName) {
			return delegate.getMappedClassName(internalName);
		}

		@Nullable
		@Override
		public String getMappedFieldName(@Nonnull String ownerName, @Nonnull String fieldName,
		                                 @Nonnull String fieldDesc) {
			return delegate.getMappedFieldName(ownerName, fieldName, fieldDesc);
		}

		@Nullable
		@Override
		public String getMappedMethodName(@Nonnull String ownerName, @Nonnull String methodName,
		                                  @Nonnull String methodDesc) {
			return delegate.getMappedMethodName(ownerName, methodName, methodDesc);
		}

		@Nullable
		@Override
		public String getMappedVariableName(@Nonnull String className, @Nonnull String methodName,
		                                    @Nonnull String methodDesc, @Nullable String name,
		                                    @Nullable String desc, int index) {
			return delegate.getMappedVariableName(className, methodName, methodDesc, name, desc, index);
		}

		@Nonnull
		@Override
		public IntermediateMappings exportIntermediate() {
			return delegate.exportIntermediate();
		}
	}
}
