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
