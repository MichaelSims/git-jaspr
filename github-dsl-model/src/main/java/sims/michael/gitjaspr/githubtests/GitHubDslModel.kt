package sims.michael.gitjaspr.githubtests

import sims.michael.gitjaspr.dataclassfragment.*

@GenerateDataClassFragmentDataClass
interface TestCase : DataClassFragment {
    val repository: NestedPropertyNotNull<Branch>
    val localWillBeDirty: BooleanPropertyNotNull

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
    val body: StringPropertyNotNull
    val prTitle: StringPropertyNotNull
    val prStartTitle: StringPropertyNotNull
    val prEndTitle: StringPropertyNotNull
    val footerLines: MapPropertyNotNull<StringPropertyNotNull>
    val willPassVerification: BooleanProperty
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
    val willBeApprovedByUserKey: StringProperty
}
