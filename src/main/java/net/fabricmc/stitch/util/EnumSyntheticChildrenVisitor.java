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

package net.fabricmc.stitch.util;

import cuchaz.enigma.translation.representation.AccessFlags;
import cuchaz.enigma.translation.representation.entry.ClassDefEntry;
import net.fabricmc.mappings.EntryTriple;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;

public class EnumSyntheticChildrenVisitor extends ClassVisitor {
    private ClassDefEntry classEntry;
    private final Map<EntryTriple, AccessFlags> enumClassChildrenAccess;

    public EnumSyntheticChildrenVisitor(int api, Map<EntryTriple, AccessFlags> enumClassChildrenAccess) {
        super(api);
        this.enumClassChildrenAccess = enumClassChildrenAccess;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        if ((access & Opcodes.ACC_ENUM) != 0) {
            this.classEntry = ClassDefEntry.parse(access, name, signature, superName, interfaces);
        }
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        if (this.classEntry != null) {
            this.enumClassChildrenAccess.put(new EntryTriple(this.classEntry.getFullName(), name, descriptor), new AccessFlags(access));
        }

        return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        if (this.classEntry != null) {
            this.enumClassChildrenAccess.put(new EntryTriple(this.classEntry.getFullName(), name, descriptor), new AccessFlags(access));
        }

        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }
}
