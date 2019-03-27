import { Reducer } from 'redux'

export enum MarkerType {
    HIGHLIGHT,
    WARNING,
    ERROR
}

type State = { readonly markers: ReadonlySet<{ line: number; type: MarkerType }> }

type Action = { type: 'markers/set'; payload: { markers: { line: number; type: MarkerType }[] } }

const initialState: State = {
    markers: new Set()
}

export const reducer: Reducer<State, Action> = (state = initialState, action) => {
    switch (action.type) {
        case 'markers/set':
            return { ...state, markers: new Set(action.payload.markers) }
    }
    return state
}
