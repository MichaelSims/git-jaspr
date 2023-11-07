package sims.michael.gitkspr.githubtests

import sims.michael.gitkspr.dataclassfragment.*

@GenerateDataClassFragmentDataClass
interface TestCase : DataClassFragment {
    val repository: NestedPropertyNotNull<Branch>
}

@GenerateDataClassFragmentDataClass
interface Branch : DataClassFragment {
    @GenerateDataClassFragmentDataClass.TestDataDslName("commit")
    val commits: ListOfNestedPropertyNotNull<Commit>
}

@GenerateDataClassFragmentDataClass
interface Commit : DataClassFragment {
    val id: IntPropertyNotNull
    @GenerateDataClassFragmentDataClass.TestDataDslName("branch")
    val branches: ListOfNestedPropertyNotNull<Branch>
    val localRefs: SetPropertyNotNull<StringPropertyNotNull>
    val remoteRefs: SetPropertyNotNull<StringPropertyNotNull>
    val title: StringPropertyNotNull
    val prTitle: StringPropertyNotNull
    val prStartTitle: StringPropertyNotNull
    val prEndTitle: StringPropertyNotNull
}
