package sims.michael.gitkspr.dataclassfragment.codegen

/**
 * Returns the name of a type without its package name. This differs from the "simple name" in that it includes all
 * enclosing classes in the case of nested class declarations.
 *
 * Example:
 * The "simple name" of `foo.bar.EnclosingClass.EnclosedClass` would be `EnclosedClass` while the "name without
 * package" would be `EnclosingClass.EnclosedClass`
 */
fun getNameWithoutPackage(packageName: String, qualifiedName: String): String {
    val pkgNameParts = packageName.split(PACKAGE_DELIMITER).filterNot(String::isBlank)
    val qualifiedNameParts = qualifiedName.split(PACKAGE_DELIMITER)
    return qualifiedNameParts.drop(pkgNameParts.size).joinToString(PACKAGE_DELIMITER)
}

const val PACKAGE_DELIMITER = "."
