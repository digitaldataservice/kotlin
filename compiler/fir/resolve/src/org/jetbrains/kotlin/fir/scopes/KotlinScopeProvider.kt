/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.scopes

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.scopes.impl.*
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassErrorType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.name.ClassId

class KotlinScopeProvider(
    val declaredMemberScopeDecorator: (
        klass: FirClass<*>,
        declaredMemberScope: FirScope,
        useSiteSession: FirSession,
        scopeSession: ScopeSession
    ) -> FirScope = { _, declaredMemberScope, _, _ -> declaredMemberScope }
) : FirScopeProvider() {
    override fun getUseSiteMemberScope(
        klass: FirClass<*>,
        useSiteSession: FirSession,
        scopeSession: ScopeSession
    ): FirTypeScope {
        return scopeSession.getOrBuild(klass.symbol, USE_SITE) {
            val declaredScope = declaredMemberScope(klass)
            val decoratedDeclaredMemberScope =
                declaredMemberScopeDecorator(klass, declaredScope, useSiteSession, scopeSession)

            // TODO: what if klass is FirAnonymousObject?
            // Counting it as object here breaks some FIR black box tests,
            // mostly because of local parent search
            val isForObject = klass is FirRegularClass && klass.classKind == ClassKind.OBJECT
            val scopes = lookupSuperTypes(klass, lookupInterfaces = true, deep = false, useSiteSession = useSiteSession)
                .mapNotNull { useSiteSuperType ->
                    if (useSiteSuperType is ConeClassErrorType) return@mapNotNull null
                    val symbol = useSiteSuperType.lookupTag.toSymbol(useSiteSession)
                    if (symbol is FirRegularClassSymbol) {
                        val substitutor = substitutor(symbol, useSiteSuperType, useSiteSession, isForObject)
                        symbol.fir.scope(
                            substitutor,
                            useSiteSession, scopeSession,
                            skipPrivateMembers = true,
                            classId = klass.classId,
                            isFromExpectClass = (klass as? FirRegularClass)?.isExpect == true
                        ).let {
                            it as? FirTypeScope ?: error("$it is expected to be FirOverrideAwareScope")
                        }
                    } else {
                        null
                    }
                }
            FirClassUseSiteMemberScope(
                useSiteSession,
                FirTypeIntersectionScope.prepareIntersectionScope(
                    useSiteSession, FirStandardOverrideChecker(useSiteSession), scopes
                ),
                decoratedDeclaredMemberScope
            )
        }
    }

    private fun substitutor(
        symbol: FirRegularClassSymbol,
        type: ConeClassLikeType,
        useSiteSession: FirSession,
        isForObject: Boolean
    ): ConeSubstitutor {
        if (type.typeArguments.isEmpty()) {
            return if (isForObject) {
                ConeSubstitutor.StubForObject
            } else {
                ConeSubstitutor.Empty
            }
        }
        val originalSubstitution = createSubstitution(symbol.fir.typeParameters, type.typeArguments, useSiteSession)
        return substitutorByMap(originalSubstitution)
    }

    override fun getStaticMemberScopeForCallables(
        klass: FirClass<*>,
        useSiteSession: FirSession,
        scopeSession: ScopeSession
    ): FirScope? {
        return when (klass.classKind) {
            ClassKind.ENUM_CLASS -> FirOnlyCallablesScope(FirStaticScope(declaredMemberScope(klass)))
            else -> null
        }
    }

    override fun getNestedClassifierScope(klass: FirClass<*>, useSiteSession: FirSession, scopeSession: ScopeSession): FirScope? {
        return nestedClassifierScope(klass)
    }
}


data class ConeSubstitutionScopeKey(
    val classId: ClassId?, val isFromExpectClass: Boolean, val substitutor: ConeSubstitutor
) : ScopeSessionKey<FirClass<*>, FirClassSubstitutionScope>()

fun FirClass<*>.unsubstitutedScope(useSiteSession: FirSession, scopeSession: ScopeSession): FirScope {
    return scopeProvider.getUseSiteMemberScope(this, useSiteSession, scopeSession)
}

internal fun FirClass<*>.scope(
    substitutor: ConeSubstitutor,
    useSiteSession: FirSession,
    scopeSession: ScopeSession,
    skipPrivateMembers: Boolean,
    classId: ClassId? = this.classId,
    isFromExpectClass: Boolean = false
): FirTypeScope {
    val basicScope = scopeProvider.getUseSiteMemberScope(
        this, useSiteSession, scopeSession
    )

    return scopeSession.getOrBuild(
        this, ConeSubstitutionScopeKey(classId, isFromExpectClass, substitutor)
    ) {
        FirClassSubstitutionScope(
            useSiteSession, basicScope, scopeSession, substitutor,
            skipPrivateMembers, classId, makeExpect = isFromExpectClass
        )
    }
}
