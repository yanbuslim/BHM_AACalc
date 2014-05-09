package com.gordoni.opal;

abstract class Utility
{
        public double range;

        public double u_0 = Double.NaN;
        public double u_inf = Double.NaN;

        public abstract double utility(double c);

        public abstract double inverse_utility(double u);

        public abstract double slope(double c);

        public abstract double inverse_slope(double s);

        protected void set_constants()
        {
	        u_0 = utility(0);
	        u_inf = utility(Double.POSITIVE_INFINITY);
	}

        public static Utility utilityFactory(Config config, String utility_function, Double eta, Double alpha, double c_shift, double c_zero, Double ce, double ce_ratio, double c1, double s1, double c2, double s2, double public_assistance, double public_assistance_phaseout_rate, double range)
        {
	        boolean linear = utility_function.equals("linear");
	        if (utility_function.equals("power") && ((eta == null && s1 == s2) || (eta != null && eta == 0)) && public_assistance == 0)
		        linear = true;
	        else if (utility_function.equals("exponential") && ((alpha == null && s1 == 0) || (alpha != null && alpha == 0)) && public_assistance == 0)
		{
		        assert(s2 == 0); // public_assistance must be positive if utility_consume_fn="power" and utility_inherit_fn="exponential" unless force_alpha.
		        linear = true;
		}
	        if (linear)
		{
		        assert(public_assistance == 0);
		        return new UtilityLinear(c_zero, s2, range);
		}
		else if (utility_function.equals("power"))
		        return new UtilityPower(config, eta, c_shift, c_zero, ce, ce_ratio, c1, s1, c2, s2, public_assistance, public_assistance_phaseout_rate, range);
		else if (utility_function.equals("exponential"))
		        return new UtilityExponential(config, alpha, c_shift, c_zero, c1, s1, c2, s2, public_assistance, public_assistance_phaseout_rate, range);
		else
		        assert(false);
		return null;
	}
}