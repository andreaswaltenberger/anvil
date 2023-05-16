package com.squareup.anvil.sample.child

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class Child @AssistedInject constructor(
  @Assisted("dad") father: String,
  // @Assisted("mom") mother: String,
) {

  @AssistedFactory
  interface Factory {
    fun create(
      @Assisted("dad") father: String,
      // @Assisted("mom") mother: String,
    ): Child
  }
}
