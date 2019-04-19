import cn from 'classnames'
import { css } from 'emotion'
import * as React from 'react'
import { colors } from '../../../colors'
import * as protocol from '../../../protobuf/protocol'
import { ObjNode } from '../../../reducers/debug/heap'
import { BaseNode } from './BaseNode'

const classes = {
    elements: cn('d-flex align-items-end'),
    element: cn(
        'd-inline-flex flex-column',
        'px-1',
        css({
            border: `0.5px solid ${colors.gray.dark}`,
            cursor: 'default',
            fontSize: '1rem',
            background: colors.primaryBlue.light
        })
    ),
    index: cn('text-truncate', css({ fontSize: '0.5rem' })),
    value: cn('text-center text-truncate', css({ fontSize: '0.75rem' })),
    bar: cn(css({ borderBottom: `1px solid ${colors.gray.dark}` }))
}

export function NumericArray(props: { node: ObjNode }) {
    const supported =
        (props.node.type === protocol.Obj.Type.ARRAY ||
            props.node.type === protocol.Obj.Type.ALIST ||
            props.node.type === protocol.Obj.Type.LLIST ||
            props.node.type === protocol.Obj.Type.SET) &&
        props.node.members.every(member => typeof member.value === 'number' && isFinite(member.value))

    if (!supported)
        return (
            <BaseNode type={props.node.languageType}>
                <div className={classes.elements}>not compatible</div>
            </BaseNode>
        )

    if (props.node.members.length === 0)
        return (
            <BaseNode type={props.node.languageType}>
                <div className={classes.elements}>empty</div>
            </BaseNode>
        )

    const computeDeltaRatios = (values: number[]) => {
        const min = Math.min(...values)
        const max = Math.max(...values)
        const delta = max - min !== 0 ? max - min : 1
        return values.map(value => (value - min) / delta)
    }

    const computeStairRatios = (values: number[]) => {
        const valuesIndices = values.map((value, i) => [value, i])
        valuesIndices.sort((a, b) => a[0] - b[0])
        const sequenceGenerator = { previous: -Infinity, sequence: 0 }
        const sequenceIndices = valuesIndices.map(([value, i]) => {
            if (sequenceGenerator.previous !== value) {
                sequenceGenerator.previous = value
                return [++sequenceGenerator.sequence, i]
            }
            return [sequenceGenerator.sequence, i]
        })
        const min = sequenceIndices[0][0]
        const max = sequenceIndices[sequenceIndices.length - 1][0]
        const delta = max - min !== 0 ? max - min : 1
        const ratioIndices = sequenceIndices.map(([value, i]) => [(value - min) / delta, i])
        ratioIndices.sort((a, b) => a[1] - b[1])
        return ratioIndices.map(([value, i]) => value)
    }

    // parameters
    const mode: 'delta' | 'stair' = 'delta'
    const showIndex = true
    const showValues = true
    const width = 30
    const height = 50

    const values = props.node.members.map(member => member.value as number)
    const ratios = mode === 'delta' ? computeDeltaRatios(values) : computeStairRatios(values)

    return (
        <BaseNode type={props.node.languageType}>
            <div className={classes.elements}>
                {ratios.map((ratio, i) => (
                    <div key={i} className={classes.element} style={{ width }} title={`${values[i]}`}>
                        <div className={classes.bar} style={{ height: `${ratio * height}px` }} />
                        {showIndex && <span className={classes.index}>{i}</span>}
                        {showValues && <span className={classes.value}>{values[i]}</span>}
                    </div>
                ))}
            </div>
        </BaseNode>
    )
}
