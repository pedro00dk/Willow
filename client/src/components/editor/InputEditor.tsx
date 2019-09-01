import * as ace from 'brace'
import cn from 'classnames'
import { css } from 'emotion'
import * as React from 'react'
import { colors } from '../../colors'
import { actions as programActions } from '../../reducers/program'
import { useDispatch, useRedux } from '../../reducers/Store'
import { EditorMarker, range, TextEditor } from './TextEditor'

const classes = {
    marker: cn('position-absolute', css({ backgroundColor: colors.blue.light }))
}

export const InputEditor = () => {
    const [editor, setEditor] = React.useState<ace.Editor>()
    const dispatch = useDispatch()
    const { fetching } = useRedux(state => ({ fetching: state.tracer.fetching }))

    React.useEffect(() => {
        if (!editor) return
        editor.renderer.setShowGutter(false)

        const onChange = (change: ace.EditorChangeEvent) =>
            dispatch(programActions.setInput(editor.session.doc.getAllLines().slice(0, -1)))

        editor.on('change', onChange)
        return () => editor.off('change', onChange)
    }, [editor])

    React.useEffect(() => {
        if (!editor) return
        editor.setReadOnly(fetching)
        Object.values(editor.session.getMarkers(false) as EditorMarker[])
            .filter(marker => marker.id > 2)
            .forEach(marker => editor.session.removeMarker(marker.id))
        if (!fetching) return
        editor.session.addMarker(range(0, 0, editor.session.getLength() - 1, 1), classes.marker, 'fullLine', false)
    }, [editor, fetching])

    return <TextEditor onEditor={setEditor} />
}
