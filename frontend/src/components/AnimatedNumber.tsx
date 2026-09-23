import { motion, useSpring, useTransform } from 'motion/react'
import { useEffect } from 'react'

/** A number that springs to its new value instead of jumping. */
export function AnimatedNumber({ value }: { value: number }) {
  const spring = useSpring(value, { stiffness: 140, damping: 20 })
  const text = useTransform(spring, (v) => Math.round(v).toLocaleString())
  useEffect(() => {
    spring.set(value)
  }, [spring, value])
  return <motion.span>{text}</motion.span>
}
