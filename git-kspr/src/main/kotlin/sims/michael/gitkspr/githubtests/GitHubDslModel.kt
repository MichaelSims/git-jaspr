package sims.michael.gitkspr.githubtests

import sims.michael.gitkspr.dataclassfragment.*
import sims.michael.gitkspr.githubtests.generatedtestdsl.CommitDataBuilder

@GenerateDataClassFragmentDataClass
interface TestCase : DataClassFragment {
    val repository: NestedPropertyNotNull<Branch>
    val localIsDirty: BooleanPropertyNotNull

    @GenerateDataClassFragmentDataClass.TestDataDslName("pullRequest")
    val pullRequests: ListOfNestedPropertyNotNull<PullRequest>
}

@GenerateDataClassFragmentDataClass
interface Branch : DataClassFragment {
    @GenerateDataClassFragmentDataClass.TestDataDslName("commit")
    val commits: ListOfNestedPropertyNotNull<Commit>
}

@GenerateDataClassFragmentDataClass
interface Commit : DataClassFragment {
    val id: StringProperty
    val committer: NestedPropertyNotNull<Ident>

    @GenerateDataClassFragmentDataClass.TestDataDslName("branch")
    val branches: ListOfNestedPropertyNotNull<Branch>
    val localRefs: SetPropertyNotNull<StringPropertyNotNull>
    val remoteRefs: SetPropertyNotNull<StringPropertyNotNull>
    val title: StringPropertyNotNull
    val prTitle: StringPropertyNotNull
    val prStartTitle: StringPropertyNotNull
    val prEndTitle: StringPropertyNotNull
    val footerLines: MapPropertyNotNull<StringPropertyNotNull>
}

@GenerateDataClassFragmentDataClass
interface Ident : DataClassFragment {
    val name: StringPropertyNotNull
    val email: StringPropertyNotNull
}

@GenerateDataClassFragmentDataClass
interface PullRequest : DataClassFragment {
    val baseRef: StringPropertyNotNull
    val headRef: StringPropertyNotNull
    val title: StringPropertyNotNull
    val body: StringPropertyNotNull
    val userKey: StringPropertyNotNull
}

/** Configure our functional test repo to fail this commit during CI/CD */
fun CommitDataBuilder<CommitData>.willFailVerification() {
    footerLines["verify-result"] = "13"
}
