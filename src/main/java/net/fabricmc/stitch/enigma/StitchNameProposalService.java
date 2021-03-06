/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.stitch.enigma;

import cuchaz.enigma.analysis.index.JarIndex;
import cuchaz.enigma.api.EnigmaPluginContext;
import cuchaz.enigma.api.service.JarIndexerService;
import cuchaz.enigma.api.service.NameProposalService;
import cuchaz.enigma.classprovider.ClassProvider;
import cuchaz.enigma.translation.representation.AccessFlags;
import cuchaz.enigma.translation.representation.TypeDescriptor;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;
import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.stitch.util.EnumSyntheticChildrenVisitor;
import net.fabricmc.stitch.util.FieldNameFinder;
import net.fabricmc.stitch.util.NameFinderVisitor;
import net.fabricmc.stitch.util.StitchUtil;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class StitchNameProposalService {
	private Map<EntryTriple, String> fieldNames;
	private final Map<EntryTriple, AccessFlags> enumClassChildrenAccess = new HashMap<>();

	private StitchNameProposalService(EnigmaPluginContext ctx) {
		ctx.registerService("stitch:jar_indexer", JarIndexerService.TYPE, ctx1 -> new JarIndexerService() {
			@Override
			public void acceptJar(Set<String> classNames, ClassProvider classProvider, JarIndex jarIndex) {
				Map<String, Set<String>> enumFields = new HashMap<>();
				Map<String, List<MethodNode>> methods = new HashMap<>();

				for (String className : classNames) {
					classProvider.get(className).accept(new NameFinderVisitor(StitchUtil.ASM_VERSION, enumFields, methods));
					classProvider.get(className).accept(new EnumSyntheticChildrenVisitor(StitchUtil.ASM_VERSION, enumClassChildrenAccess));
				}

				try {
					fieldNames = new FieldNameFinder().findNames(enumFields, methods);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		});

		ctx.registerService("stitch:name_proposal", NameProposalService.TYPE, ctx12 -> (obfEntry, remapper) -> {
			if(obfEntry instanceof FieldEntry){
				FieldEntry fieldEntry = (FieldEntry) obfEntry;
				EntryTriple key = new EntryTriple(fieldEntry.getContainingClass().getFullName(), fieldEntry.getName(), fieldEntry.getDesc().toString());
				return Optional.ofNullable(fieldNames.get(key));
			}
			return Optional.empty();
		});

		ctx.registerService("stitch:enum_synthetics_name_proposal", NameProposalService.TYPE, ctx1 -> (obfEntry, remapper) -> {
			if (obfEntry instanceof FieldEntry) {
				FieldEntry fieldEntry = (FieldEntry) obfEntry;
				EntryTriple key = new EntryTriple(fieldEntry.getContainingClass().getFullName(), fieldEntry.getName(), fieldEntry.getDesc().toString());
				boolean isClassInstanceArray = fieldEntry.getDesc().isArray() && fieldEntry.getDesc().getArrayType().equals(TypeDescriptor.of(fieldEntry.getContainingClass().getFullName()));
				if (enumClassChildrenAccess.containsKey(key) && enumClassChildrenAccess.get(key).isSynthetic() && isClassInstanceArray) {
					return Optional.of("$VALUES");
				}
			} else if (obfEntry instanceof MethodEntry) {
				MethodEntry methodEntry = (MethodEntry) obfEntry;
				EntryTriple key = new EntryTriple(methodEntry.getContainingClass().getFullName(), methodEntry.getName(), methodEntry.getDesc().toString());
				boolean isClassInstanceArray = methodEntry.getDesc().getReturnDesc().isArray() && methodEntry.getDesc().getReturnDesc().getArrayType().equals(TypeDescriptor.of(methodEntry.getContainingClass().getFullName()));
				if (enumClassChildrenAccess.containsKey(key) && enumClassChildrenAccess.get(key).isSynthetic() && isClassInstanceArray) {
					return Optional.of("$values");
				}
			}

			return Optional.empty();
		});
	}

	public static void register(EnigmaPluginContext ctx) {
		new StitchNameProposalService(ctx);
	}
}
