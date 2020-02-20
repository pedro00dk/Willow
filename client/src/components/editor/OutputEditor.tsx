import ace from 'brace'
import React from 'react'
import { useSelection } from '../../reducers/Store'
import { TextEditor } from './TextEditor'

export const OutputEditor = () => {
    const editor = React.useRef<ace.Editor>()
    const output = React.useRef<string[]>([])

    React.useLayoutEffect(() => {
        editor.current.renderer.setShowGutter(false)
    }, [editor.current])

    useSelection(async (state, previousState) => {
        const tracer = state.tracer
        const previousTracer = previousState.tracer
        if (!tracer.available || tracer.steps === previousTracer?.steps) return
        output.current = tracer.steps.reduce((acc, step) => {
            const previousContent = acc[acc.length - 1] ?? ''
            const prints = step.prints ?? ''
            const threw = step.threw?.cause ?? step.threw?.exception.traceback ?? ''
            acc.push(`${previousContent}${prints}${threw}`)
            return acc
        }, [] as string[])
    })

    useSelection(async (state, previousState) => {
        const tracer = state.tracer
        const previousTracer = previousState.tracer
        if (!editor.current || !tracer.available || tracer.index === previousTracer?.index) return
        editor.current.session.doc.setValue(output.current[state.tracer.index])
        editor.current.scrollToLine(editor.current.session.getLength(), true, true, undefined)
    })

    return <TextEditor onEditor={React.useCallback(e => (editor.current = e), [])} />
}
