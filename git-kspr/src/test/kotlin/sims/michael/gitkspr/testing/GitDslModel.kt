package sims.michael.gitkspr.testing

import sims.michael.gitkspr.dataclassfragment.*

@GenerateDataClassFragmentDataClass
interface Commit : DataClassFragment {
    val id: IntPropertyNotNull
    val branches: ListOfNestedPropertyNotNull<Branch>
    val localRefs: SetPropertyNotNull<StringPropertyNotNull>
    val remoteRefs: SetPropertyNotNull<StringPropertyNotNull>
    val title: StringPropertyNotNull
    val prTitle: StringPropertyNotNull
    val prStartTitle: StringPropertyNotNull
    val prEndTitle: StringPropertyNotNull
}

@GenerateDataClassFragmentDataClass
interface Branch : DataClassFragment {
    val commits: ListOfNestedPropertyNotNull<Commit>
}
