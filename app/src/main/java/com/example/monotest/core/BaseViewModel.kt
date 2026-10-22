package com.example.monotest.core

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

abstract class BaseViewModel<State, Action, NavEvent, UiEvent>(initialState: State) : ViewModel() {
    protected val _state = MutableStateFlow(initialState)
    open val state: StateFlow<State> = _state.asStateFlow()

    private val _navEvents = MutableSharedFlow<NavEvent>(0, 1, BufferOverflow.DROP_OLDEST)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    private val _uiEvents = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents: Flow<UiEvent> = _uiEvents.receiveAsFlow()

    abstract fun onAction(action: Action)

    protected fun updateState(reducer: State.() -> State) = _state.update(reducer)

    protected suspend fun sendNavEvent(event: NavEvent) = _navEvents.emit(event)

    protected fun launchNavEvent(event: NavEvent) = viewModelScope.launch { _navEvents.emit(event) }

    protected suspend fun sendUiEvent(event: UiEvent) = _uiEvents.send(event)

    protected fun launchUiEvent(event: UiEvent) = viewModelScope.launch { _uiEvents.send(event) }
}