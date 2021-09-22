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

package net.fabricmc.stitch.commands.tinyv2;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.util.Pair;

// TODO: update javadoc
/**
 * Merges a tiny file with 2 columns (namespaces) of mappings, with another tiny file that has
 * the same namespace as the first column and a different namespace as the second column.
 * The first column of the output will contain the shared namespace,
 * the second column of the output would be the second namespace of input a,
 * and the third column of the output would be the second namespace of input b
 * <p>
 * Descriptors will remain as-is (using the namespace of the first column)
 * <p>
 * <p>
 * For example:
 * <p>
 * Input A:
 * intermediary                 named
 * c    net/minecraft/class_123      net/minecraft/somePackage/someClass
 * m   (Lnet/minecraft/class_124;)V  method_1234 someMethod
 * <p>
 * Input B:
 * intermediary                 official
 * c    net/minecraft/class_123      a
 * m   (Lnet/minecraft/class_124;)V  method_1234 a
 * <p>
 * The output will be:
 * <p>
 * intermediary                 named                                  official
 * c    net/minecraft/class_123      net/minecraft/somePackage/someClass    a
 * m   (Lnet/minecraft/class_124;)V  method_1234 someMethod    a
 * <p>
 * <p>
 * After intermediary-named mappings are obtained,
 * and official-intermediary mappings are obtained and swapped using CommandReorderTinyV2, Loom merges them using this command,
 * and then reorders it to official-intermediary-named using CommandReorderTinyV2 again.
 * This is a convenient way of storing all the mappings in Loom.
 */
public class CommandMergeTinyV2 extends Command {
	public CommandMergeTinyV2() {
		super("mergeTinyV2");
	}

	/**
	 * <input-a> and <input-b> are the tiny files to be merged. The result will be written to <output>.
	 */
	@Override
	public String getHelpString() {
		return "<input-a> <input-b> [<input-c>...] <output>";
	}

	@Override
	public boolean isArgumentCountValid(int count) {
		return count >= 3;
	}

	@Override
	public void run(String[] args) throws IOException {
		Path[] inputs = new Path[args.length - 1];
		for (int i = 0; i < args.length - 1; ++i) {
			inputs[i] = Paths.get(args[i]);
		}
		Path output = Paths.get(args[args.length - 1]);

		List<TinyFile> tinyFiles = new ArrayList<>();
		TinyFile tinyFileA = TinyV2Reader.read(inputs[0]);
		tinyFiles.add(tinyFileA);

		TinyHeader headerA = tinyFileA.getHeader();
		if (headerA.getNamespaces().size() < 2) {
			throw new IllegalArgumentException(inputs[0] + " must have at least 2 namespaces.");
		}

		String baseNamespace = headerA.getNamespaces().get(0);
		for (int i = 1; i < inputs.length; ++i) {
			Path input = inputs[i];
			TinyFile tinyFile = TinyV2Reader.read(input);
			tinyFiles.add(tinyFile);
			TinyHeader header = tinyFile.getHeader();
			List<String> namespaces = header.getNamespaces();

			if (header.getNamespaces().size() < 2) {
				throw new IllegalArgumentException(inputs[i] + " must have at least 2 namespaces.");
			}

			if (!namespaces.get(0).equals(baseNamespace)) {
				throw new IllegalArgumentException(String.format("The input tiny files must have the same namespaces as the first column. " +
						"(%s has %s instead of %s)", input, namespaces.get(0), baseNamespace));
			}
		}

		System.out.println("Merging " + inputs[0] + " with " + Arrays.stream(inputs).skip(1).map(Path::toString).collect(Collectors.joining(", ")));
		TinyFile mergedFile = merge(tinyFiles);

		TinyV2Writer.write(mergedFile, output);
		System.out.println("Merged mappings written to " + output);
	}

	private TinyFile merge(List<TinyFile> inputs) {
		//TODO: how to merge properties?

		TinyHeader mergedHeader = mergeHeaders(inputs.stream().map(TinyFile::getHeader).collect(Collectors.toList()));

		List<String> keyUnion = keyUnion(inputs.stream().map(TinyFile::getClassEntries).collect(Collectors.toList()));

		List<Map<String, TinyClass>> inputsClasses = inputs.stream().map(TinyFile::mapClassesByFirstNamespace).collect(Collectors.toList());
		List<TinyClass> mergedClasses = map(keyUnion, key -> {
			List<TinyClass> classes = inputsClasses.stream().map(inputClasses -> matchEnclosingClassIfNeeded(key, inputClasses.get(key), inputClasses)).collect(Collectors.toList());
			return mergeClasses(key, classes);
		});

		return new TinyFile(mergedHeader, mergedClasses);
	}

	private TinyClass matchEnclosingClassIfNeeded(String key, TinyClass tinyClass, Map<String, TinyClass> mappings) {
		if (tinyClass == null) {
			String partlyMatchedClassName = matchEnclosingClass(key, mappings);
			return new TinyClass(Arrays.asList(key, partlyMatchedClassName));
		} else {
			return tinyClass;
		}
	}

	/**
	 * Takes something like net/minecraft/class_123$class_124 that doesn't have a mapping, tries to find net/minecraft/class_123
	 * , say the mapping of net/minecraft/class_123 is path/to/someclass and then returns a class of the form
	 * path/to/someclass$class124
	 */
	@Nonnull
	private String matchEnclosingClass(String sharedName, Map<String, TinyClass> inputBClassBySharedNamespace) {
		String[] path = sharedName.split(escape("$"));
		int parts = path.length;
		for (int i = parts - 2; i >= 0; i--) {
			String currentPath = String.join("$", Arrays.copyOfRange(path, 0, i + 1));
			TinyClass match = inputBClassBySharedNamespace.get(currentPath);

			if (match != null && !match.getClassNames().get(1).isEmpty()) {
				return match.getClassNames().get(1)
								+ "$" + String.join("$", Arrays.copyOfRange(path, i + 1, path.length));

			}
		}

		return sharedName;
	}

	private TinyClass mergeClasses(String sharedClassName, List<TinyClass> classes) {
		List<String> mergedNames = mergeNames(sharedClassName, classes);
		List<String> mergedComments = mergeComments(classes.stream().map(TinyClass::getComments).collect(Collectors.toList()));

		List<Pair<String, String>> methodKeyUnion = union(classes.stream().map(clazz -> mapToFirstNamespaceAndDescriptor(clazz).collect(Collectors.toList())).collect(Collectors.toList()));
		List<Map<Pair<String, String>, TinyMethod>> methods = classes.stream().map(TinyClass::mapMethodsByFirstNamespaceAndDescriptor).collect(Collectors.toList());
		List<TinyMethod> mergedMethods = map(methodKeyUnion, (Pair<String, String> k) ->
				mergeMethods(k.getLeft(), methods.stream().map(method -> method.get(k)).collect(Collectors.toList())));

		List<String> fieldKeyUnion = keyUnion(classes.stream().map(TinyClass::getFields).collect(Collectors.toList()));
		List<Map<String, TinyField>> fields = classes.stream().map(TinyClass::mapFieldsByFirstNamespace).collect(Collectors.toList());
		List<TinyField> mergedFields = map(fieldKeyUnion, k -> mergeFields(k, fields.stream().map(map -> map.get(k)).collect(Collectors.toList())));

		return new TinyClass(mergedNames, mergedMethods, mergedFields, mergedComments);
	}

	private static final TinyMethod EMPTY_METHOD = new TinyMethod(null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

	private TinyMethod mergeMethods(String sharedMethodName, List<TinyMethod> methods) {
		List<String> mergedNames = mergeNames(sharedMethodName, methods);
		methods.replaceAll(method -> method == null ? EMPTY_METHOD : method);
		List<String> mergedComments = mergeComments(methods.stream().map(TinyMethod::getComments).collect(Collectors.toList()));

		String descriptor = methods.get(0).getMethodDescriptorInFirstNamespace() != null ? methods.get(0).getMethodDescriptorInFirstNamespace()
				: methods.get(1).getMethodDescriptorInFirstNamespace();
		if (descriptor == null) throw new RuntimeException("no descriptor for key " + sharedMethodName);

		// TODO: Fix parameters
		List<TinyMethodParameter> mergedParameters = new ArrayList<>();
		addParameters(methods, mergedParameters);

		List<TinyLocalVariable> mergedLocalVariables = new ArrayList<>();
		addLocalVariables(methods, mergedLocalVariables);

		return new TinyMethod(descriptor, mergedNames, mergedParameters, mergedLocalVariables, mergedComments);
	}

	private void addParameters(List<TinyMethod> methods, List<TinyMethodParameter> addTo) {
		for (TinyMethod method : methods) {
			for (TinyMethodParameter localVariable : method.getParameters()) {
				List<String> names = new ArrayList<>(localVariable.getParameterNames());
				addTo.add(new TinyMethodParameter(localVariable.getLvIndex(), names, localVariable.getComments()));
			}
		}
	}

	private void addLocalVariables(List<TinyMethod> methods, List<TinyLocalVariable> addTo) {
		for (TinyMethod method : methods) {
			for (TinyLocalVariable localVariable : method.getLocalVariables()) {
				List<String> names = new ArrayList<>(localVariable.getLocalVariableNames());
				addTo.add(new TinyLocalVariable(localVariable.getLvIndex(), localVariable.getLvStartOffset(),
						localVariable.getLvTableIndex(), names, localVariable.getComments()));
			}
		}
	}

	private TinyField mergeFields(String sharedFieldName, List<TinyField> fields) {
		List<String> mergedNames = mergeNames(sharedFieldName, fields);
		List<String> mergedComments = mergeComments(fields.stream().map(field -> field != null ? field.getComments() : Collections.<String>emptyList()).collect(Collectors.toList()));

		String descriptor = fields.stream().filter(Objects::nonNull).findFirst().map(TinyField::getFieldDescriptorInFirstNamespace).orElse(null);
		if (descriptor == null) throw new RuntimeException("no descriptor for key " + sharedFieldName);

		return new TinyField(descriptor, mergedNames, mergedComments);
	}

	private TinyHeader mergeHeaders(List<TinyHeader> headers) {
		TinyHeader headerA = headers.get(0);
		List<String> namespaces = new ArrayList<>(headerA.getNamespaces());
		for (int i = 1; i < headers.size(); ++i) {
			for (String namespace : headers.get(i).getNamespaces()) {
				if (!namespaces.contains(namespace)) {
					namespaces.add(namespace);
				}
			}
		}
		// TODO: how should versions and properties be merged?
		return new TinyHeader(namespaces, headerA.getMajorVersion(), headerA.getMinorVersion(), headerA.getProperties());
	}

	private List<String> mergeComments(List<Collection<String>> comments) {
		return union(comments);
	}

	private <T extends Mapping> List<String> keyUnion(List<Collection<T>> mappings) {
		return union(mappings.stream().map(c -> c.stream().map(m -> m.getMapping().get(0)).collect(Collectors.toList())).collect(Collectors.toList()));
	}

	private Stream<Pair<String, String>> mapToFirstNamespaceAndDescriptor(TinyClass tinyClass) {
		return tinyClass.getMethods().stream().map(m -> Pair.of(m.getMapping().get(0), m.getMethodDescriptorInFirstNamespace()));
	}

	private List<String> mergeNames(String key, List<? extends Mapping> mappings) {
		List<String> merged = new ArrayList<>();
		merged.add(key);
		mappings.forEach(mapping -> {
			if (mapping != null) {
				for (int i = 1; i < mapping.getMapping().size(); ++i) {
					String m = mapping.getMapping().get(i);
					merged.add(!m.isEmpty() ? m : key);
				}
			}
		});

		return merged;
	}

	private boolean mappingExists(@Nullable Mapping mapping) {
		return mapping != null && !mapping.getMapping().get(1).isEmpty();
	}

	private <T> List<T> union(List<Collection<T>> lists) {
		Set<T> set = new HashSet<>();

		lists.forEach(set::addAll);

		return new ArrayList<>(set);
	}

	private <T> List<T> union(Collection<T> list1, Collection<T> list2) {
		Set<T> set = new HashSet<T>();

		set.addAll(list1);
		set.addAll(list2);

		return new ArrayList<T>(set);
	}

	private static String escape(String str) {
		return Pattern.quote(str);
	}

	private <S, E> List<E> map(List<S> from, Function<S, E> mapper) {
		return from.stream().map(mapper).collect(Collectors.toList());
	}
}
