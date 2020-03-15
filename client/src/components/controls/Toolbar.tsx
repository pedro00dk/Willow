import { css } from 'emotion'
import React from 'react'
import playImage from '../../../public/buttons/play.png'
import stepImage from '../../../public/buttons/stepInto.png'
import { actions as optionsActions } from '../../reducers/options'
import { useDispatch, useSelection } from '../../reducers/Store'
import { actions as tracerActions } from '../../reducers/tracer'

const classes = {
    container: 'd-flex align-items-center',
    image: `mx-3 ${css({ width: '1.5rem' })}`,
    option: 'mx-3',
    optionLabel: 'mr-2'
}

const styles = {
    image: (available: boolean, rotation: number = 0) => ({
        cursor: available && 'pointer',
        filter: !available && 'grayscale(100%)',
        transform: `rotate(${rotation}deg)`
    })
}

export const Toolbar = () => {
    const dispatch = useDispatch()
    const { options, tracer } = useSelection(state => ({ options: state.options, tracer: state.tracer }))
    const canTrace = !tracer.fetching
    const canStepBack = tracer.available && tracer.index > 0
    const canStepForward = tracer.available && tracer.index < tracer.steps.length - 1
    const stepMessage = tracer.available && `Step ${tracer.index + 1} of ${tracer.steps.length}`

    return (
        <div className={classes.container}>
            <img
                className={classes.image}
                style={styles.image(canTrace)}
                src={playImage}
                title='Start tracing'
                onClick={() => canTrace && dispatch(tracerActions.trace())}
            />
            <img
                className={classes.image}
                style={styles.image(canStepBack, 90)}
                src={stepImage}
                title='Step backward'
                onClick={() => canStepBack && dispatch(tracerActions.stepIndex('backward', 'into'))}
            />
            <img
                className={classes.image}
                style={styles.image(canStepForward, 270)}
                src={stepImage}
                title='Step forward'
                onClick={() => canStepForward && dispatch(tracerActions.stepIndex('forward', 'into'))}
            />
            <div className={classes.option}>
                <span className={classes.optionLabel}>{'Enable visualization'}</span>
                <input
                    type='checkbox'
                    checked={options.enableVisualization}
                    onChange={event => dispatch(optionsActions.setEnableVisualization(event.target.checked))}
                />
            </div>
            <div className={classes.option}>
                <span className={classes.optionLabel}>{'Preserve Layout'}</span>
                <input
                    type='checkbox'
                    checked={options.preserveLayout}
                    onChange={event => dispatch(optionsActions.setPreserveLayout(event.target.checked))}
                />
            </div>
            <span className={classes.option}>{stepMessage}</span>
        </div>
    )
}
